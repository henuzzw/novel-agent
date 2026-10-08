package com.novelagent.agent.application;

import com.novelagent.agent.api.AutomationRunResponse;
import com.novelagent.agent.api.CreateAutomationRunRequest;
import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.domain.ManuscriptStatus;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

/**
 * 自动创作任务。
 *
 * <p>协调后台串行创作、等待作者确认及有限质量修订。耗时生成在状态存储事务之外执行；后续更新检查当前尝试，失败不自动无限重试。</p>
 */
@Service
public class AutomationService {
    private static final Logger log = LoggerFactory.getLogger(AutomationService.class);
    private final AutomationRunStore store;
    private final WritingService writing;
    private final QualityReviewService quality;
    private final CanonCommitRepository canon;
    private final TaskExecutor executor;
    private final ChapterAutomationPlanner planner = new ChapterAutomationPlanner();

    public AutomationService(AutomationRunStore store, WritingService writing, QualityReviewService quality, CanonCommitRepository canon,
            @Qualifier("automationExecutor") TaskExecutor executor) {
        this.store = store;
        this.writing = writing;
        this.quality = quality;
        this.canon = canon;
        this.executor = executor;
    }

    /**
     * 幂等创建范围任务，仅认领仍 PENDING 的任务并派发后台执行；返回当前进度，不等待全部章节生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestKey 自动任务的幂等键，同键重复请求必须保持参数一致。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public AutomationRunResponse create(UUID projectId, UUID requestKey, CreateAutomationRunRequest request) {
        AutomationRun run = store.create(projectId, requestKey, request);
        if (run.getStatus() != AutomationStatus.PENDING) return AutomationRunResponse.from(run);
        store.claimPending(projectId, run.getId()).ifPresent(this::dispatch);
        return get(projectId, run.getId());
    }

    /**
     * 按当前来源与状态恢复任务；恢复不是绕过版本校验，也不是无限自动重试授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public AutomationRunResponse resume(UUID projectId, UUID id) {
        AutomationRun run = store.claim(projectId, id);
        dispatch(run);
        return get(projectId, id);
    }

    private void dispatch(AutomationRun run) {
        try {
            executor.execute(() -> execute(run));
        } catch (RuntimeException exception) {
            log.warn("Automation dispatch rejected runId={} exceptionType={}", run.getId(), exception.getClass().getSimpleName());
            store.update(run.getProjectId(), run.getId(), run.getAttempt(), value -> value.fail("EXECUTOR_UNAVAILABLE"));
        }
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<AutomationRunResponse> list(UUID projectId) {
        return store.list(projectId).stream().map(AutomationRunResponse::from).toList();
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public AutomationRunResponse get(UUID projectId, UUID id) {
        return AutomationRunResponse.from(store.get(projectId, id));
    }

    /**
     * 持久化自动任务取消请求；执行器在状态更新边界停止，不保证正在进行的供应商调用立刻终止。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public AutomationRunResponse cancel(UUID projectId, UUID id) {
        return AutomationRunResponse.from(store.cancel(projectId, id));
    }

    private void execute(AutomationRun claimed) {
        UUID projectId = claimed.getProjectId();
        UUID id = claimed.getId();
        int attempt = claimed.getAttempt();
        try {
            while (store.update(projectId, id, attempt, AutomationRun::checkpoint)) {
                AutomationRun run = store.get(projectId, id);
                if (!store.outlineUnchanged(run)) {
                    store.update(projectId, id, attempt, value -> value.waitForUser("当前大纲已变化，请取消任务并按新大纲创建任务"));
                    return;
                }
                int chapter = run.getCurrentChapter();
                if (store.chapterOccurred(run) && !canon.existsByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter)) {
                    store.update(projectId, id, attempt, value -> value.waitForUser("本章是导入作品的已发生章节，请先完成事实确认和正史登记"));
                    return;
                }
                if (chapter > 1 && !canon.existsByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter - 1)) {
                    store.update(projectId, id, attempt, value -> value.waitForUser("请先提交上一章正史，确保后续章节使用已确认事实"));
                    return;
                }
                var manuscript = writing.latestManuscript(projectId, chapter).orElse(null);
                var qualityReport = run.isQualityReviewEnabled() && manuscript != null
                        && manuscript.status() == ManuscriptStatus.DRAFT ? quality.latest(projectId, chapter)
                                .filter(report -> run.getProvider().name().equals(report.generatorType())).orElse(null) : null;
                var decision = planner.next(run.getOutlineId(),
                        null,
                        null,
                        manuscript,
                        writing.latestReview(projectId, chapter).orElse(null),
                        canon.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter)
                                .map(commit -> commit.getManuscriptVersionId()).orElse(null),
                        run.isQualityReviewEnabled(), qualityReport, run.getMaxAutoRevisionRounds(),
                        run.getUsedAutoRevisionRounds(), run.getProvider());
                if (decision.action() == ChapterAutomationPlanner.Action.WAIT) {
                    store.update(projectId, id, attempt, value -> value.waitForUser(decision.reason()));
                    return;
                }
                if (decision.action() == ChapterAutomationPlanner.Action.NEXT_CHAPTER) {
                    store.update(projectId, id, attempt, AutomationRun::advanceChapter);
                    continue;
                }
                if (!store.update(projectId, id, attempt, value -> {
                    if (value.checkpoint()) value.beginStep(decision.action().name());
                })) return;
                GenerateWritingRequest request = new GenerateWritingRequest(run.getProvider(), run.getInstruction(),
                        GenerationMode.REGENERATE, null, null);
                UUID artifact = switch (decision.action()) {
                    case MANUSCRIPT -> writing.generateManuscript(projectId, chapter, request).id();
                    case QUALITY_REVIEW -> quality.generate(projectId, chapter, run.getProvider(), run.getInstruction()).id();
                    case QUALITY_REVISION -> quality.revise(projectId, chapter, qualityReport.id(), decision.issueIds(),
                            run.getProvider(), run.getInstruction()).id();
                    case REVIEW -> writing.generateReview(projectId, chapter, request).id();
                    default -> throw new IllegalStateException("无效的自动生成阶段");
                };
                store.update(projectId, id, attempt, value -> value.completeStep(artifact));
            }
        } catch (RuntimeException exception) {
            log.warn("Automation failed runId={} projectId={} exceptionType={}", id, projectId,
                    exception.getClass().getSimpleName());
            store.update(projectId, id, attempt, value -> value.fail(exception.getClass().getSimpleName()));
        }
    }
}
