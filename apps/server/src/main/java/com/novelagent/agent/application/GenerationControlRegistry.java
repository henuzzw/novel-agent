package com.novelagent.agent.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Component
public class GenerationControlRegistry {
    public static final String REQUEST_HEADER = "X-Generation-Request-Id";
    private final Map<UUID, ActiveCall> runs = new ConcurrentHashMap<>();
    private final Map<UUID, ActiveCall> requests = new ConcurrentHashMap<>();
    private record BackgroundRequest(UUID id, BooleanSupplier cancelled) { }
    private final ThreadLocal<BackgroundRequest> background = new ThreadLocal<>();

    /** 后台任务逐次复用业务请求编号；取消状态在供应商调用开始前再次核对。 */
    public AutoCloseable background(UUID id, BooleanSupplier cancelled) {
        var previous = background.get();
        background.set(new BackgroundRequest(id, cancelled));
        return () -> { if (previous == null) background.remove(); else background.set(previous); };
    }

    public ActiveCall start(UUID projectId, UUID runId) {
        var request = background.get();
        if (request != null && request.cancelled().getAsBoolean()) throw new GenerationStoppedException();
        UUID requestId = request == null ? currentRequestId() : request.id();
        ActiveCall call = new ActiveCall(projectId, runId, requestId, Thread.currentThread());
        if (requestId != null && requests.putIfAbsent(requestId, call) != null) {
            throw new IllegalStateException("该生成请求编号正在使用，请使用新的请求编号。");
        }
        runs.put(runId, call);
        try {
            if (request != null && request.cancelled().getAsBoolean()) throw new GenerationStoppedException();
        } catch (RuntimeException failure) { call.close(); throw failure; }
        return call;
    }

    public void stopRun(UUID projectId, UUID runId) { stop(projectId, runs.get(runId)); }
    public void stopRequest(UUID projectId, UUID requestId) { stop(projectId, requests.get(requestId)); }

    private void stop(UUID projectId, ActiveCall call) {
        if (call == null || !call.projectId.equals(projectId)) {
            throw new GenerationStopConflictException("没有可停止的模型调用；请求可能尚未开始、已经结束或正在保存结果，请刷新状态。");
        }
        call.stop();
    }

    private static UUID currentRequestId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return null;
        String value = attributes.getRequest().getHeader(REQUEST_HEADER);
        try { return value == null ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    public final class ActiveCall implements AutoCloseable {
        private final UUID projectId;
        private final UUID runId;
        private final UUID requestId;
        private final Thread worker;
        private boolean stopped;
        private boolean saving;
        private boolean closed;

        private ActiveCall(UUID projectId, UUID runId, UUID requestId, Thread worker) {
            this.projectId = projectId;
            this.runId = runId;
            this.requestId = requestId;
            this.worker = worker;
        }

        private synchronized void stop() {
            if (stopped) return;
            if (saving || closed) throw new GenerationStopConflictException("模型调用已结束，正在保存结果，不能停止；请刷新状态。");
            stopped = true;
            worker.interrupt();
        }

        public synchronized boolean isStopped() { return stopped; }

        // Cancellation and the hand-off to output validation have a single linearization point.
        public synchronized void beginSaving() {
            if (stopped) throw new GenerationStoppedException();
            saving = true;
        }

        @Override public synchronized void close() {
            closed = true;
            runs.remove(runId, this);
            if (requestId != null) requests.remove(requestId, this);
            if (stopped && Thread.currentThread() == worker) Thread.interrupted();
        }
    }
}
