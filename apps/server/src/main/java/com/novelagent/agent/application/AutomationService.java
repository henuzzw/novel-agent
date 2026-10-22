package com.novelagent.agent.application;

import com.novelagent.agent.api.dto.AutomationRunResponse;
import com.novelagent.agent.api.dto.CreateAutomationRunRequest;
import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.writing.application.DraftLoopService;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.domain.DraftLoopRun;
import com.novelagent.writing.domain.ManuscriptStatus;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 章节范围任务编排。逐章复用 A/B/C 编辑循环，按任务授权决定发布与记忆整理；任务认领、额度和恢复状态由存储层持久化。
 */
@Service
public class AutomationService {
    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private final AutomationRunStore store;
    private final WritingService writing;
    private final DraftLoopService loops;
    private final CanonCommitRepository canon;
    private final TaskExecutor executor;
    private final AutomationDraftPublisher publisher;

    public AutomationService(
            AutomationRunStore store,
            WritingService writing,
            DraftLoopService loops,
            CanonCommitRepository canon,
            @Qualifier("automationExecutor") TaskExecutor executor,
            AutomationDraftPublisher publisher) {
        this.store = store;
        this.writing = writing;
        this.loops = loops;
        this.canon = canon;
        this.executor = executor;
        this.publisher = publisher;
    }

    /**
     * 按幂等键创建章节推进任务；仅认领待执行任务并派发后台线程，重复请求不重复启动。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param key 章节范围任务的创建幂等键
     * @param request 章节范围、轮次和生成额度，以及自动发布与记忆整理授权
     */
    public AutomationRunResponse create(
            UUID projectId, UUID key, CreateAutomationRunRequest request) {
        var run = store.create(projectId, key, request);
        if (run.getStatus() == AutomationStatus.PENDING)
            store.claimPending(projectId, run.getId()).ifPresent(this::dispatch);
        return get(projectId, run.getId());
    }

    /**
     * 认领可恢复任务并派发后台执行，沿用既有进度与额度；执行时继续核对大纲和正文来源。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    public AutomationRunResponse resume(UUID projectId, UUID id) {
        dispatch(store.claim(projectId, id));
        return get(projectId, id);
    }

    /**
     * 响应事务提交后的发布通知，幂等认领等待中的范围任务；满足发布及记忆条件后才继续。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     * @param chapter 目标章节号，从 1 开始
     */
    public void published(UUID projectId, UUID id, int chapter) {
        store.claimPublication(projectId, id, chapter)
                .filter(run -> run.getStatus() == AutomationStatus.RUNNING)
                .ifPresent(this::dispatch);
    }

    private void dispatch(AutomationRun run) {
        try {
            executor.execute(() -> execute(run));
        } catch (RuntimeException failure) {
            log.warn("Chapter advance dispatch rejected runId={}", run.getId());
            store.update(
                    run.getProjectId(),
                    run.getId(),
                    run.getAttempt(),
                    value -> value.fail("EXECUTOR_UNAVAILABLE"));
        }
    }

    /**
     * 读取当前作者项目的章节推进任务列表，不派发生成请求。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    public List<AutomationRunResponse> list(UUID projectId) {
        return store.list(projectId).stream().map(AutomationRunResponse::from).toList();
    }

    /**
     * 读取指定章节推进任务的持久状态、当前章节和失败详情。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    public AutomationRunResponse get(UUID projectId, UUID id) {
        return AutomationRunResponse.from(store.get(projectId, id));
    }

    /**
     * 持久化取消请求并停止已绑定的单章编辑循环；已保存草稿和已发布正文保留。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    public AutomationRunResponse cancel(UUID projectId, UUID id) {
        var run = store.cancel(projectId, id);
        if (run.getCurrentLoopId() != null) loops.cancel(projectId, run.getCurrentLoopId());
        return get(projectId, id);
    }

    private void execute(AutomationRun claimed) {
        UUID projectId = claimed.getProjectId();
        UUID id = claimed.getId();
        int attempt = claimed.getAttempt();
        try {
            while (store.update(projectId, id, attempt, AutomationRun::checkpoint)) {
                var run = store.get(projectId, id);
                if (!store.outlineUnchanged(run)) {
                    wait(run, "当前大纲已变化，请取消任务并按新大纲创建任务");
                    return;
                }
                int chapter = run.getCurrentChapter();
                var manuscript = writing.latestManuscript(projectId, chapter).orElse(null);
                var commit =
                        canon.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter)
                                .orElse(null);
                if (commit != null) {
                    if (manuscript != null
                            && !commit.getManuscriptVersionId().equals(manuscript.id())) {
                        wait(run, "本章已有正史且另有新稿，请由作者处理发布版本");
                        return;
                    }
                    store.update(projectId, id, attempt, AutomationRun::advanceChapter);
                    continue;
                }
                if (store.chapterOccurred(run)) {
                    wait(run, "本章是导入作品的已发生章节，请先完成事实确认和正史登记");
                    return;
                }
                if (chapter > 1
                        && !canon.existsByProjectIdAndChapterNumberAndActiveTrue(
                                projectId, chapter - 1)) {
                    wait(run, "请先提交上一章正史，确保后续章节使用已确认事实");
                    return;
                }
                if (manuscript != null && manuscript.status() == ManuscriptStatus.AUTHOR_ACCEPTED) {
                    awaitPublication(run, "本章已确认，请由作者发布，发布成功后自动继续下一章");
                    return;
                }
                var result =
                        loops.runForAutomation(
                                run,
                                manuscript == null,
                                new DraftLoopService.Progress() {
                                    @Override
                                    public boolean before(DraftLoopRun child) {
                                        return store.update(
                                                projectId,
                                                id,
                                                attempt,
                                                value ->
                                                        value.beginStep(
                                                                "DRAFT_"
                                                                        + child.getPhase().name()));
                                    }

                                    @Override
                                    public void after(DraftLoopRun child) {
                                        store.update(
                                                projectId,
                                                id,
                                                attempt,
                                                value -> {
                                                    if (child.getStatus()
                                                                    == DraftLoopRun.Status.CANCELLED
                                                            || child.getStopReason()
                                                                    == DraftLoopRun.StopReason
                                                                            .SOURCE_CHANGED
                                                            || child.getStopReason()
                                                                    == DraftLoopRun.StopReason
                                                                            .INTERRUPTED) {
                                                        value.fail(
                                                                "DRAFT_"
                                                                        + (child.getStopReason()
                                                                                        == null
                                                                                ? child.getStatus()
                                                                                : child
                                                                                        .getStopReason()));
                                                    } else
                                                        value.completeStep(child.getManuscriptId());
                                                });
                                    }
                                });
                if (result.getStatus() == DraftLoopRun.Status.FAILED) {
                    String detail =
                            "第 "
                                    + chapter
                                    + " 章 · "
                                    + result.getPhase()
                                    + " 阶段失败："
                                    + ModelFailureDetails.sanitize(
                                            result.getErrorMessage() == null
                                                    ? "模型调用或输出校验失败"
                                                    : result.getErrorMessage());
                    store.update(
                            projectId,
                            id,
                            attempt,
                            value -> value.fail("DRAFT_LOOP_FAILED", detail));
                    return;
                }
                if (result.getStatus() == DraftLoopRun.Status.CANCELLED) {
                    wait(run, "本章自动编辑已停止，请检查草稿后手动继续或取消章节推进");
                    return;
                }
                if (result.getStopReason() == DraftLoopRun.StopReason.SOURCE_CHANGED
                        || result.getStopReason() == DraftLoopRun.StopReason.INTERRUPTED) {
                    wait(run, "正文或创作依据已变化，或执行被中断；请检查后手动继续");
                    return;
                }
                if (result.getManuscriptId() == null || result.active()) {
                    wait(run, "尚未完成有效草稿，请检查本章自动编辑任务");
                    return;
                }
                if (run.isAutoPublish() && publisher.publish(run, result.getId())) return;
                awaitPublication(
                        run, "本章A/B/C已结束（" + result.getStopReason() + "），请由作者检查并发布；发布成功后自动继续下一章");
                return;
            }
        } catch (RuntimeException failure) {
            log.warn(
                    "Chapter advance failed projectId={} runId={} exceptionType={}",
                    projectId,
                    id,
                    failure.getClass().getSimpleName());
            store.update(
                    projectId,
                    id,
                    attempt,
                    value ->
                            value.fail(
                                    failure.getClass().getSimpleName(),
                                    ModelFailureDetails.from(failure).detail()));
        }
    }

    private void wait(AutomationRun run, String reason) {
        store.update(
                run.getProjectId(),
                run.getId(),
                run.getAttempt(),
                value -> value.waitForUser(reason));
    }

    private void awaitPublication(AutomationRun run, String reason) {
        store.update(
                run.getProjectId(),
                run.getId(),
                run.getAttempt(),
                value -> value.waitForPublication(reason));
        // Publication may race with the final loop state write; only an armed waiting state can
        // advance.
        published(run.getProjectId(), run.getId(), run.getCurrentChapter());
    }
}
