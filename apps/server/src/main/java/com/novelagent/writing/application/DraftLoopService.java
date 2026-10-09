package com.novelagent.writing.application;

import com.novelagent.agent.application.GenerationControlRegistry;
import com.novelagent.agent.application.GenerationStopConflictException;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.DraftLoopRun;
import com.novelagent.writing.infrastructure.DraftLoopModel;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/** 串行 A→B→C→B 闭环，无轮间人工操作；失败保留已完成轮次，不自动重试付费调用。 */
@Service
public class DraftLoopService {
    private static final Logger log = LoggerFactory.getLogger(DraftLoopService.class);
    private final DraftLoopStore store;
    private final DraftLoopContext contexts;
    private final DraftLoopModel model;
    private final GenerationControlRegistry controls;
    private final TaskExecutor executor;

    public DraftLoopService(DraftLoopStore store, DraftLoopContext contexts, DraftLoopModel model,
            GenerationControlRegistry controls, @Qualifier("automationExecutor") TaskExecutor executor) {
        this.store = store; this.contexts = contexts; this.model = model; this.controls = controls; this.executor = executor;
    }

    public DraftLoopRun create(UUID projectId, UUID key, int chapter, ModelProvider provider, boolean writeFirst, int maxRounds) {
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE || maxRounds < 1 || maxRounds > 10) {
            throw new IllegalArgumentException("自动编辑请选择真实模型，轮次上限1至10");
        }
        var run = store.existing(projectId, key, chapter, provider, writeFirst, maxRounds)
                .orElseGet(() -> store.create(projectId, key, chapter, provider, writeFirst, maxRounds, contexts.freeze(projectId, chapter, provider)));
        if (store.claim(projectId, run.getId())) {
            try { executor.execute(() -> execute(projectId, run.getId())); }
            catch (RuntimeException failure) { store.fail(projectId, run.getId(), "后台执行器不可用，请稍后重试"); }
        }
        return store.get(projectId, run.getId());
    }

    public DraftLoopRun cancel(UUID projectId, UUID id) {
        var run = store.cancel(projectId, id);
        try { controls.stopRequest(projectId, id); }
        catch (GenerationStopConflictException ignored) { /* 阶段间没有模型调用；数据库状态仍阻止下一阶段和迟到保存。 */ }
        return run;
    }

    void execute(UUID projectId, UUID id) {
        try (var ignored = controls.background(id, () -> !store.get(projectId, id).active())) {
            while (store.ready(projectId, id)) {
                var run = store.get(projectId, id);
                log.info("Draft loop stage projectId={} runId={} phase={} round={}", projectId, id, run.getPhase(), run.getRounds().size());
                switch (run.getPhase()) {
                    case A -> model.write(run, output -> store.wrote(projectId, id, output));
                    case B -> {
                        var draft = store.body(run);
                        model.check(run, draft, report -> store.checked(projectId, id, draft, report));
                    }
                    case C -> model.judge(run, store.body(run), run.getRounds().getLast().check(), output -> store.judged(projectId, id, output));
                }
            }
        } catch (GenerationStoppedException failure) {
            store.cancel(projectId, id);
        } catch (Exception failure) {
            log.warn("Draft loop failed projectId={} runId={} exceptionType={}", projectId, id, failure.getClass().getSimpleName());
            store.fail(projectId, id, failure.getMessage());
        }
    }
}
