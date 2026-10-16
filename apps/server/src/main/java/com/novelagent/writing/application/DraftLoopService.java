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
                .orElseGet(() -> {
                    if (writeFirst) contexts.requireReadyForWriting(projectId, chapter);
                    return store.create(projectId, key, chapter, provider, writeFirst, maxRounds, contexts.freeze(projectId, chapter, provider));
                });
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
        execute(projectId, id, new Progress() { });
    }

    public interface Progress {
        default boolean before(DraftLoopRun run) { return true; }
        default void after(DraftLoopRun run) { }
    }

    /** Execute the owned child on the caller's worker, avoiding nested executor queues and a second implementation of A/B/C. */
    public DraftLoopRun runForAutomation(com.novelagent.agent.domain.AutomationRun parent, boolean writeFirst, Progress progress) {
        UUID projectId = parent.getProjectId();
        int chapter = parent.getCurrentChapter();
        var retry = store.retryForAutomation(parent);
        if (retry.isPresent()) {
            var run = retry.get();
            if (run.getStatus() == DraftLoopRun.Status.RUNNING) execute(projectId, run.getId(), progress);
            return store.get(projectId, run.getId());
        }
        if (writeFirst) contexts.requireReadyForWriting(projectId, chapter);
        var frozen = contexts.freeze(projectId, chapter, parent.getProvider());
        if (parent.getInstruction() != null && !parent.getInstruction().isBlank()) {
            var basis = frozen.basis();
            frozen = new DraftLoopContext.Source(new DraftLoopRun.Basis(basis.fingerprint(),
                    basis.prompt() + "\n【作者授权的本次章节推进要求；不覆盖已确认事实及硬约束】\n" + parent.getInstruction(),
                    basis.writingBasis()), frozen.context(), frozen.latest());
        }
        UUID key = UUID.nameUUIDFromBytes((parent.getId() + ":" + parent.getAttempt() + ":" + chapter)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var run = store.createForAutomation(projectId, key, chapter, parent.getProvider(), writeFirst,
                parent.getMaxLoopRounds(), frozen, parent.getId(), parent.getAttempt());
        if (store.claim(projectId, run.getId())) execute(projectId, run.getId(), progress);
        return store.get(projectId, run.getId());
    }

    private void execute(UUID projectId, UUID id, Progress progress) {
        try (var ignored = controls.background(id, () -> !store.get(projectId, id).active())) {
            while (store.ready(projectId, id)) {
                var run = store.get(projectId, id);
                if (!progress.before(run)) { store.cancel(projectId, id); break; }
                log.info("Draft loop stage projectId={} runId={} phase={} round={}", projectId, id, run.getPhase(), run.getRounds().size());
                switch (run.getPhase()) {
                    case A -> {
                        contexts.requireReadyForWriting(projectId, run.getChapterNumber());
                        model.write(run, output -> store.wrote(projectId, id, output));
                    }
                    case B -> {
                        var draft = store.body(run);
                        model.check(run, draft, report -> store.checked(projectId, id, draft, report));
                    }
                    case C -> model.judge(run, store.body(run), run.getRounds().getLast().check(), output -> store.judged(projectId, id, output));
                }
                progress.after(store.get(projectId, id));
            }
        } catch (GenerationStoppedException failure) {
            store.cancel(projectId, id);
        } catch (Exception failure) {
            log.warn("Draft loop failed projectId={} runId={} exceptionType={}", projectId, id, failure.getClass().getSimpleName());
            store.fail(projectId, id, failure.getMessage());
        }
    }
}
