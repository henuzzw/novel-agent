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

    public AutomationRunResponse create(UUID projectId, UUID requestKey, CreateAutomationRunRequest request) {
        AutomationRun run = store.create(projectId, requestKey, request);
        if (run.getStatus() != AutomationStatus.PENDING) return AutomationRunResponse.from(run);
        store.claimPending(projectId, run.getId()).ifPresent(this::dispatch);
        return get(projectId, run.getId());
    }

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

    public List<AutomationRunResponse> list(UUID projectId) {
        return store.list(projectId).stream().map(AutomationRunResponse::from).toList();
    }

    public AutomationRunResponse get(UUID projectId, UUID id) {
        return AutomationRunResponse.from(store.get(projectId, id));
    }

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
                        writing.latestContract(projectId, chapter).orElse(null),
                        writing.latestContractReview(projectId, chapter).orElse(null),
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
                    case CONTRACT -> writing.generateContract(projectId, chapter, request).id();
                    case CONTRACT_REVIEW -> writing.generateContractReview(projectId, chapter, request).id();
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
