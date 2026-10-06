package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterContractReviewResponse;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.domain.ChapterContractStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

class AutomationServiceTest {
    private final AutomationRunStore store = mock(AutomationRunStore.class);
    private final WritingService writing = mock(WritingService.class);
    private final QualityReviewService quality = mock(QualityReviewService.class);
    private final CanonCommitRepository canon = mock(CanonCommitRepository.class);
    private final AutomationRun run = AutomationRun.create(ChapterAutomationPlannerTest.PROJECT,
            ChapterAutomationPlannerTest.OUTLINE, 1, 3, ModelProvider.LOCAL_TEMPLATE, null);
    private final AtomicReference<ChapterContractResponse> contract = new AtomicReference<>();
    private final AtomicReference<ChapterContractReviewResponse> contractReview = new AtomicReference<>();
    private AutomationService service;

    @BeforeEach
    void setUp() {
        service = new AutomationService(store, writing, quality, canon, Runnable::run);
        when(store.claim(run.getProjectId(), run.getId())).thenAnswer(call -> {
            run.start(Instant.now());
            return run;
        });
        when(store.get(run.getProjectId(), run.getId())).thenReturn(run);
        when(store.outlineUnchanged(any())).thenReturn(true);
        when(store.update(eq(run.getProjectId()), eq(run.getId()), anyInt(), any())).thenAnswer(call -> {
            if (run.getAttempt() != (int) call.getArgument(2) || run.getStatus() != AutomationStatus.RUNNING) return false;
            Consumer<AutomationRun> mutation = call.getArgument(3);
            mutation.accept(run);
            return run.getStatus() == AutomationStatus.RUNNING;
        });
        when(writing.latestContract(run.getProjectId(), 1)).thenAnswer(call -> Optional.ofNullable(contract.get()));
        when(writing.latestContractReview(run.getProjectId(), 1)).thenAnswer(call -> Optional.ofNullable(contractReview.get()));
        when(writing.generateContract(eq(run.getProjectId()), eq(1), any())).thenAnswer(call -> {
            contract.set(ChapterAutomationPlannerTest.contract(ChapterContractStatus.DRAFT));
            return contract.get();
        });
        when(writing.generateContractReview(eq(run.getProjectId()), eq(1), any())).thenAnswer(call -> {
            contractReview.set(ChapterAutomationPlannerTest.contractReview(ChapterAutomationPlannerTest.CONTRACT, 2));
            return contractReview.get();
        });
    }

    @Test
    void generatesContractAndReviewThenWaitsWithoutWritingNextChapter() {
        var response = service.resume(run.getProjectId(), run.getId());
        assertThat(response.status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(response.steps()).hasSize(2).allMatch(step -> step.artifactId() != null);
        verify(writing, never()).generateManuscript(any(), anyInt(), any());
        verify(writing, never()).generateContract(any(), eq(2), any());
        service.resume(run.getProjectId(), run.getId());
        assertThat(run.getSteps()).hasSize(2);
    }

    @Test
    void recordsFailedStageAndRetriesWithoutRepeatingSavedContract() {
        when(writing.generateContractReview(eq(run.getProjectId()), eq(1), any()))
                .thenThrow(new IllegalStateException("模型不可用"));
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.FAILED);
        when(writing.generateContractReview(eq(run.getProjectId()), eq(1), any())).thenAnswer(call -> {
            contractReview.set(ChapterAutomationPlannerTest.contractReview(ChapterAutomationPlannerTest.CONTRACT, 2));
            return contractReview.get();
        });
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        verify(writing).generateContract(eq(run.getProjectId()), eq(1), any());
        assertThat(run.getSteps()).hasSize(3);
    }

    @Test
    void stopsAfterInFlightGenerationWhenCancelled() {
        when(writing.generateContract(eq(run.getProjectId()), eq(1), any())).thenAnswer(call -> {
            run.cancel();
            return ChapterAutomationPlannerTest.contract(ChapterContractStatus.DRAFT);
        });
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.CANCELLED);
        assertThat(run.getSteps().getFirst().status()).isEqualTo("SUCCEEDED");
        verify(writing, never()).generateContractReview(any(), anyInt(), any());
    }

    @Test
    void stopsWhenOutlineChangesOrChapterWasImported() {
        when(store.outlineUnchanged(any())).thenReturn(false);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("大纲已变化");
        verify(writing, never()).generateContract(any(), anyInt(), any());
        when(store.outlineUnchanged(any())).thenReturn(true);
        when(store.chapterOccurred(any())).thenReturn(true);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("已发生章节");
    }

    @Test
    void advancesAcrossCommittedChaptersAndFinishesRange() {
        CanonCommit commit = mock(CanonCommit.class);
        when(commit.getManuscriptVersionId()).thenReturn(ChapterAutomationPlannerTest.MANUSCRIPT);
        when(canon.findByProjectIdAndChapterNumberAndActiveTrue(eq(run.getProjectId()), anyInt())).thenReturn(Optional.of(commit));
        when(canon.existsByProjectIdAndChapterNumberAndActiveTrue(eq(run.getProjectId()), anyInt())).thenReturn(true);
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.SUCCEEDED);
        assertThat(run.getCurrentChapter()).isEqualTo(3);
        verify(writing, never()).generateContract(any(), anyInt(), any());
    }

    @Test
    void marksExecutorRejectionAsRecoverableFailure() {
        TaskExecutor executor = task -> { throw new IllegalStateException("繁忙"); };
        service = new AutomationService(store, writing, quality, canon, executor);
        assertThat(service.resume(run.getProjectId(), run.getId()).errorCode()).isEqualTo("EXECUTOR_UNAVAILABLE");
    }
}
