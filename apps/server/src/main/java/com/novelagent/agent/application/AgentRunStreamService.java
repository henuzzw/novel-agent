package com.novelagent.agent.application;

import com.novelagent.agent.api.AgentRunOutputResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 模型响应订阅。
 *
 * <p>管理 SSE 订阅、定时快照推送和终态连接清理。只订阅已有任务，不启动模型；断开页面连接不等于取消任务。</p>
 */
@Service
public class AgentRunStreamService {
    private final AgentRunQueryService queries;
    private final Map<UUID, Subscription> subscriptions = new ConcurrentHashMap<>();

    public AgentRunStreamService(AgentRunQueryService queries) { this.queries = queries; }

    /**
     * 为已有模型任务创建 SSE 订阅，注册断开和超时清理；找不到任务时不创建连接。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<SseEmitter> open(UUID projectId, UUID runId) {
        var initial = queries.output(projectId, runId);
        if (initial.isEmpty()) return Optional.empty();
        var emitter = new SseEmitter(1_800_000L);
        UUID id = UUID.randomUUID();
        var subscription = new Subscription(projectId, runId, emitter);
        emitter.onCompletion(() -> subscriptions.remove(id));
        emitter.onTimeout(() -> { subscriptions.remove(id); emitter.complete(); });
        emitter.onError(error -> subscriptions.remove(id));
        subscriptions.put(id, subscription);
        send(id, subscription, initial.get());
        return Optional.of(emitter);
    }

    /**
     * 逐个读取订阅任务快照并推送变化，终态或失效订阅会清理；定时观测不驱动任务生成。
     */
    @Scheduled(fixedDelay = 1000)
    public void tick() {
        subscriptions.forEach((id, subscription) -> {
            try {
                var value = queries.output(subscription.projectId, subscription.runId);
                if (value.isEmpty()) { subscriptions.remove(id); subscription.emitter.complete(); }
                else send(id, subscription, value.get());
            } catch (RuntimeException exception) {
                subscriptions.remove(id);
                subscription.emitter.completeWithError(exception);
            }
        });
    }

    private void send(UUID id, Subscription subscription, AgentRunOutputResponse value) {
        synchronized (subscription) {
            try {
                if (!value.equals(subscription.last)) {
                    subscription.emitter.send(SseEmitter.event().name("output").data(value));
                    subscription.last = value;
                } else if (++subscription.idleTicks % 15 == 0) {
                    subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
                }
                if (!"RUNNING".equals(value.status())) {
                    subscriptions.remove(id);
                    subscription.emitter.complete();
                }
            } catch (IOException | IllegalStateException exception) {
                subscriptions.remove(id);
                subscription.emitter.complete();
            }
        }
    }

    private static final class Subscription {
        private final UUID projectId;
        private final UUID runId;
        private final SseEmitter emitter;
        private AgentRunOutputResponse last;
        private int idleTicks;
        private Subscription(UUID projectId, UUID runId, SseEmitter emitter) {
            this.projectId = projectId; this.runId = runId; this.emitter = emitter;
        }
    }
}
