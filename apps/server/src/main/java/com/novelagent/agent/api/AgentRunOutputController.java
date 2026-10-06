package com.novelagent.agent.api;

import com.novelagent.agent.application.AgentRunQueryService;
import com.novelagent.agent.application.AgentRunStreamService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 模型响应观测。
 *
 * <p>展示已保存或本进程正在接收的响应，并通过 SSE 订阅状态变化。该通道只观测，不代表生成结果已校验或已发布。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs/{runId}")
public class AgentRunOutputController {
    private final AgentRunQueryService queries;
    private final AgentRunStreamService streams;

    public AgentRunOutputController(AgentRunQueryService queries, AgentRunStreamService streams) {
        this.queries = queries; this.streams = streams;
    }

    /**
     * 返回指定任务的响应详情，不触发模型调用；找不到项目内任务时返回 404。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     */
    @GetMapping("/response")
    public ResponseEntity<AgentRunOutputResponse> response(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return queries.output(projectId, runId).map(value -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(value)).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 订阅已有模型任务的 SSE 快照，禁止缓存并关闭代理缓冲；连接断开不表示模型任务已取消。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return streams.open(projectId, runId).map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(value)).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
