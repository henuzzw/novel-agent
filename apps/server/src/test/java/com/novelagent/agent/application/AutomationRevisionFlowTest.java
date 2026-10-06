package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.QualityReviewResponse;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ReviewIssue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AutomationRevisionFlowTest {
    private final AutomationRunStore store = mock(AutomationRunStore.class);
    private final WritingService writing = mock(WritingService.class);
    private final QualityReviewService quality = mock(QualityReviewService.class);
    private final CanonCommitRepository canon = mock(CanonCommitRepository.class);
    private final AutomationService service = new AutomationService(store, writing, quality, canon, Runnable::run);
    private ManuscriptResponse manuscript = ChapterAutomationPlannerTest.manuscript(ManuscriptStatus.DRAFT);
    private QualityReviewResponse report;
    private boolean cleanAfterRevision;

    private AutomationRun fixture(int limit) {
        var run = AutomationRun.create(ChapterAutomationPlannerTest.PROJECT, ChapterAutomationPlannerTest.OUTLINE,
                UUID.randomUUID(), 1, 1, ModelProvider.DEEPSEEK, null, true, 1, limit);
        when(store.claim(run.getProjectId(), run.getId())).thenAnswer(call -> { run.start(Instant.now()); return run; });
        when(store.get(run.getProjectId(), run.getId())).thenReturn(run);
        when(store.outlineUnchanged(run)).thenReturn(true);
        when(store.update(eq(run.getProjectId()), eq(run.getId()), anyInt(), any())).thenAnswer(call -> {
            if (run.getAttempt() != (int) call.getArgument(2) || run.getStatus() != AutomationStatus.RUNNING) return false;
            Consumer<AutomationRun> mutation = call.getArgument(3);
            mutation.accept(run);
            return run.getStatus() == AutomationStatus.RUNNING;
        });
        when(writing.latestContract(run.getProjectId(), 1)).thenReturn(Optional.of(ChapterAutomationPlannerTest.contract(ChapterContractStatus.APPROVED)));
        when(writing.latestManuscript(run.getProjectId(), 1)).thenAnswer(call -> Optional.of(manuscript));
        when(quality.latest(run.getProjectId(), 1)).thenAnswer(call -> Optional.ofNullable(report));
        when(quality.generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.DEEPSEEK), any())).thenAnswer(call -> {
            report = checked(cleanAfterRevision && manuscript.baseManuscriptVersionId() != null ? List.of() : issues());
            return report;
        });
        when(quality.revise(eq(run.getProjectId()), eq(1), any(), eq(List.of("Q1")), eq(ModelProvider.DEEPSEEK), any()))
                .thenAnswer(call -> {
                    var original = manuscript;
                    manuscript = new ManuscriptResponse(UUID.randomUUID(), original.projectId(), original.sourceContractVersionId(),
                            original.id(), null, 1, 2, "manuscript/1", ManuscriptStatus.DRAFT, "DEEPSEEK", null,
                            original.content(), List.of("修正标点"), 0, null, null);
                    return manuscript;
                });
        return run;
    }

    @Test void revisesRechecksAndWaitsWithoutAcceptingEvenWhenClean() {
        var run = fixture(10);
        cleanAfterRevision = true;
        var result = service.resume(run.getProjectId(), run.getId());
        assertThat(result.status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(result.waitingReason()).contains("作者确认");
        assertThat(result.steps()).extracting(step -> step.stage()).containsExactly("QUALITY_REVIEW", "QUALITY_REVISION", "QUALITY_REVIEW");
        assertThat(result.usedAutoRevisionRounds()).isEqualTo(1);
        assertThat(manuscript.status()).isEqualTo(ManuscriptStatus.DRAFT);
        assertThat(report.sourceManuscriptId()).isEqualTo(manuscript.id());
        service.resume(run.getProjectId(), run.getId());
        assertThat(run.getSteps()).hasSize(3);
        verify(writing, never()).acceptManuscript(any(), any(), any(Long.class));
        verify(writing, never()).generateReview(any(), anyInt(), any());
        verify(quality, never()).revise(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test void structuralOrWarningSuggestionsNeverGainAutomaticSceneAuthorization() {
        var run = fixture(10);
        report = checked(List.of(new ReviewIssue("Q1", "INFO", "SCENE", "场景组织", "原文", "调整组织", false)));
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(run.getUsedAutoRevisionRounds()).isZero();
        verify(quality, never()).revise(any(), anyInt(), any(), any(), any(), any());
        verify(quality, never()).revise(any(), anyInt(), any(), any(), any(), any(), any());
    }

    @Test void repeatedIssueStopsAtRoundCapAndResumeCannotResetIt() {
        var run = fixture(10);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("轮数上限");
        service.resume(run.getProjectId(), run.getId());
        assertThat(run.getSteps()).hasSize(3);
        verify(quality).revise(eq(run.getProjectId()), eq(1), any(), eq(List.of("Q1")), eq(ModelProvider.DEEPSEEK), any());
    }

    @Test void budgetDoesNotAllowRevisionWithoutRoomForRecheck() {
        var run = fixture(2);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("复检");
        assertThat(run.getUsedGenerationSteps()).isEqualTo(1);
        verify(quality, never()).revise(any(), anyInt(), any(), any(), any(), any());
    }

    @Test void failedCheckConsumesBudgetAndNoMoreModelWorkIsDispatched() {
        var run = fixture(1);
        when(quality.generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.DEEPSEEK), any())).thenThrow(new IllegalStateException());
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.FAILED);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("生成次数上限");
        assertThat(run.getUsedGenerationSteps()).isEqualTo(1);
        verify(quality).generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.DEEPSEEK), any());
    }

    @Test void failedRevisionConsumesRoundAndIsNotRetriedAutomatically() {
        var run = fixture(10);
        when(quality.revise(eq(run.getProjectId()), eq(1), any(), any(), eq(ModelProvider.DEEPSEEK), any()))
                .thenThrow(new IllegalStateException("来源变化"));
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.FAILED);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("轮数上限");
        assertThat(run.getSteps()).hasSize(2);
    }

    @Test void cancellationDuringRevisionPreservesCandidateAndSkipsFurtherChecks() {
        var run = fixture(10);
        when(quality.revise(eq(run.getProjectId()), eq(1), any(), any(), eq(ModelProvider.DEEPSEEK), any()))
                .thenAnswer(call -> { run.cancel(); return manuscript; });
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.CANCELLED);
        assertThat(run.getSteps()).hasSize(2);
        assertThat(run.getSteps().getLast().artifactId()).isEqualTo(manuscript.id());
        verify(quality).generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.DEEPSEEK), any());
    }

    private QualityReviewResponse checked(List<ReviewIssue> issues) {
        var base = ChapterAutomationPlannerTest.quality(manuscript.id(), manuscript.version(), true, issues);
        return new QualityReviewResponse(base.id(), base.projectId(), 1, 1, base.sourceManuscriptId(), base.sourceManuscriptRowVersion(),
                "DEEPSEEK", true, base.content(), null);
    }

    private static List<ReviewIssue> issues() {
        return List.of(new ReviewIssue("Q1", "INFO", "FLUENCY", "连续标点", "。。", "复核标点", false));
    }
}
