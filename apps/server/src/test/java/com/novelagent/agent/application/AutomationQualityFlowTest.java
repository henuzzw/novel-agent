package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.QualityReviewResponse;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ManuscriptStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomationQualityFlowTest {
    private final AutomationRunStore store = mock(AutomationRunStore.class);
    private final WritingService writing = mock(WritingService.class);
    private final QualityReviewService quality = mock(QualityReviewService.class);
    private final CanonCommitRepository canon = mock(CanonCommitRepository.class);
    private final AutomationRun run = AutomationRun.create(ChapterAutomationPlannerTest.PROJECT,
            ChapterAutomationPlannerTest.OUTLINE, UUID.randomUUID(), 1, 1, ModelProvider.LOCAL_TEMPLATE, null, true);
    private final AtomicReference<QualityReviewResponse> report = new AtomicReference<>();
    private final AutomationService service = new AutomationService(store, writing, quality, canon, Runnable::run);

    @BeforeEach void setup() {
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
        when(writing.latestManuscript(run.getProjectId(), 1)).thenReturn(Optional.of(ChapterAutomationPlannerTest.manuscript(ManuscriptStatus.DRAFT)));
        when(quality.latest(run.getProjectId(), 1)).thenAnswer(call -> Optional.ofNullable(report.get()));
        when(quality.generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.LOCAL_TEMPLATE), any())).thenAnswer(call -> checked());
    }
    private QualityReviewResponse checked() {
        report.set(ChapterAutomationPlannerTest.quality(ChapterAutomationPlannerTest.MANUSCRIPT, 0, true, List.of()));
        return report.get();
    }
    @Test void checksOnceThenReusesReportWithoutWritingOrApprovingAnything() {
        var result = service.resume(run.getProjectId(), run.getId());
        assertThat(result.status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().getFirst().stage()).isEqualTo("QUALITY_REVIEW");
        assertThat(result.steps().getFirst().artifactId()).isEqualTo(report.get().id());
        service.resume(run.getProjectId(), run.getId());
        assertThat(run.getSteps()).hasSize(1);
        verify(quality).generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.LOCAL_TEMPLATE), any());
        verify(writing, never()).generateManuscript(any(), anyInt(), any());
        verify(writing, never()).generateReview(any(), anyInt(), any());
    }
    @Test void retryOnlyRepeatsFailedCheckNotTheSavedManuscript() {
        when(quality.generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.LOCAL_TEMPLATE), any()))
                .thenThrow(new IllegalStateException("检查失败"));
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.FAILED);
        when(quality.generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.LOCAL_TEMPLATE), any())).thenAnswer(call -> checked());
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(run.getSteps()).extracting(step -> step.status()).containsExactly("FAILED", "SUCCEEDED");
        verify(writing, never()).generateManuscript(any(), anyInt(), any());
    }
    @Test void cancellationKeepsInFlightReportAndStopsBeforeNextStage() {
        when(quality.generate(eq(run.getProjectId()), eq(1), eq(ModelProvider.LOCAL_TEMPLATE), any())).thenAnswer(call -> {
            run.cancel(); return checked();
        });
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.CANCELLED);
        assertThat(run.getSteps().getFirst().status()).isEqualTo("SUCCEEDED");
        verify(writing, never()).generateReview(any(), anyInt(), any());
    }
    @Test void acceptedManuscriptWaitsOnlyForAuthorPublication() {
        when(writing.latestManuscript(run.getProjectId(), 1)).thenReturn(Optional.of(ChapterAutomationPlannerTest.manuscript(ManuscriptStatus.AUTHOR_ACCEPTED)));
        when(writing.latestReview(run.getProjectId(), 1)).thenReturn(Optional.of(ChapterAutomationPlannerTest.review(ChapterAutomationPlannerTest.MANUSCRIPT, List.of())));
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("确认并发布");
        verifyNoInteractions(quality);
        assertThat(run.getSteps()).isEmpty();
    }
    @Test void reportsFromDifferentProviderAreNotReusedAsRequestedCheck() {
        var original = checked();
        report.set(new QualityReviewResponse(original.id(), original.projectId(), 1, 1, original.sourceManuscriptId(),
                0, "DEEPSEEK", true, original.content(), null));
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(report.get().generatorType()).isEqualTo("LOCAL_TEMPLATE");
        assertThat(run.getSteps()).hasSize(1);
    }
}
