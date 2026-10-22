package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.novelagent.agent.domain.*;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.writing.api.dto.ManuscriptResponse;
import com.novelagent.writing.application.*;
import com.novelagent.writing.domain.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AutomationServiceTest {
    private final AutomationRunStore store = mock(AutomationRunStore.class);
    private final WritingService writing = mock(WritingService.class);
    private final DraftLoopService loops = mock(DraftLoopService.class);
    private final CanonCommitRepository canon = mock(CanonCommitRepository.class);
    private final AutomationRun run = AutomationRun.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            1, 2, ModelProvider.DEEPSEEK, null, true, 0, 100);
    private final AutomationDraftPublisher publisher = mock(AutomationDraftPublisher.class);
    private final AutomationService service = new AutomationService(store, writing, loops, canon, Runnable::run, publisher);
    private DraftLoopRun child;

    @BeforeEach void setup() {
        run.configureLoop(3);
        when(store.claim(run.getProjectId(), run.getId())).thenAnswer(call -> { run.start(Instant.now()); return run; });
        when(store.get(run.getProjectId(), run.getId())).thenReturn(run);
        when(store.outlineUnchanged(run)).thenReturn(true);
        when(store.update(eq(run.getProjectId()), eq(run.getId()), anyInt(), any())).thenAnswer(call -> {
            if (run.getAttempt() != (int) call.getArgument(2) || run.getStatus() != AutomationStatus.RUNNING) return false;
            Consumer<AutomationRun> mutation = call.getArgument(3); mutation.accept(run);
            return run.getStatus() == AutomationStatus.RUNNING;
        });
        when(writing.latestManuscript(any(), anyInt())).thenReturn(Optional.empty());
        child = DraftLoopRun.create(run.getProjectId(), UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, true, 3,
                new DraftLoopRun.Basis("source", "资料", null), null);
        child.claim();
        when(loops.runForAutomation(eq(run), anyBoolean(), any())).thenAnswer(call -> {
            DraftLoopService.Progress progress = call.getArgument(2);
            var content = new ManuscriptContent("标题", "正文", "摘要", List.of());
            progress.before(child);
            child.wrote(ManuscriptVersion.create(UUID.randomUUID(), run.getProjectId(), null, 1, 1, "DEEPSEEK", null, null, content, List.of()), content.body());
            progress.after(child);
            progress.before(child); child.checked(content, new DraftCheck("无问题", List.of())); progress.after(child);
            return child;
        });
    }

    @Test void usesSharedLoopThenWaitsForAuthorPublication() {
        var result = service.resume(run.getProjectId(), run.getId());
        assertThat(result.status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(result.waitingForPublication()).isTrue();
        assertThat(result.steps()).extracting(AutomationStep::stage).containsExactly("DRAFT_A", "DRAFT_B");
        assertThat(result.currentChapter()).isEqualTo(1);
        verify(loops).runForAutomation(eq(run), eq(true), any());
        verify(writing, never()).generateManuscript(any(), anyInt(), any());
    }

    @Test void existingDraftStartsAtBAndAcceptedDraftOnlyAwaitsPublication() {
        var manuscript = ManuscriptVersion.create(UUID.randomUUID(), run.getProjectId(), null, 1, 1, "DEEPSEEK", null, null,
                new ManuscriptContent("标题", "正文", "摘要", List.of()), List.of());
        when(writing.latestManuscript(run.getProjectId(), 1)).thenReturn(Optional.of(ManuscriptResponse.from(manuscript)));
        service.resume(run.getProjectId(), run.getId());
        verify(loops).runForAutomation(eq(run), eq(false), any());
        clearInvocations(loops);
        manuscript.accept();
        when(writing.latestManuscript(run.getProjectId(), 1)).thenReturn(Optional.of(ManuscriptResponse.from(manuscript)));
        service.resume(run.getProjectId(), run.getId());
        verifyNoInteractions(loops);
        assertThat(run.isWaitingForPublication()).isTrue();
    }

    @Test void publicationClaimStartsNextChapterAndDuplicateNotificationDoesNothing() {
        run.start(Instant.now()); run.waitForPublication("发布");
        when(store.claimPublication(run.getProjectId(), run.getId(), 1)).thenAnswer(call -> {
            if (!run.isWaitingForPublication() || run.getCurrentChapter() != 1) return Optional.empty();
            run.start(Instant.now()); run.advanceChapter(); return Optional.of(run);
        });
        when(canon.existsByProjectIdAndChapterNumberAndActiveTrue(run.getProjectId(), 1)).thenReturn(true);
        when(loops.runForAutomation(eq(run), anyBoolean(), any())).thenReturn(child);
        child.stop(DraftLoopRun.StopReason.C_NO_CHANGE);
        service.published(run.getProjectId(), run.getId(), 1);
        service.published(run.getProjectId(), run.getId(), 1);
        assertThat(run.getCurrentChapter()).isEqualTo(2);
        verify(loops, times(1)).runForAutomation(eq(run), eq(true), any());
    }

    @Test void failedChildDoesNotAutomaticallyRetryOrArmPublication() {
        child.fail("供应商超时");
        when(loops.runForAutomation(eq(run), anyBoolean(), any())).thenReturn(child);
        assertThat(service.resume(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.FAILED);
        assertThat(run.isWaitingForPublication()).isFalse();
        verify(loops, times(1)).runForAutomation(eq(run), eq(true), any());
    }

    @Test void failedCheckExposesPhaseAndFieldWithoutLeakingCredentials() {
        child.wrote(ManuscriptVersion.create(UUID.randomUUID(), run.getProjectId(), null, 1, 1, "DEEPSEEK", null, null,
                new ManuscriptContent("标题", "正文", "摘要", List.of()), List.of()), "正文");
        child.fail("JSON响应输出校验失败：$.issues[0].evidence：字段类型错误 Bearer private-token");
        when(loops.runForAutomation(eq(run), anyBoolean(), any())).thenAnswer(call -> {
            DraftLoopService.Progress progress = call.getArgument(2); progress.before(child); return child;
        });
        var result = service.resume(run.getProjectId(), run.getId());
        assertThat(result.failureDetail()).contains("第 1 章", "B 阶段", "evidence", "[REDACTED]").doesNotContain("private-token");
        assertThat(result.steps().getLast().status()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("DRAFT_LOOP_FAILED");
    }

    @Test void outlineChangesAndImportedChaptersStopBeforeLoop() {
        when(store.outlineUnchanged(run)).thenReturn(false);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("大纲已变化");
        when(store.outlineUnchanged(run)).thenReturn(true); when(store.chapterOccurred(run)).thenReturn(true);
        assertThat(service.resume(run.getProjectId(), run.getId()).waitingReason()).contains("已发生章节");
        verifyNoInteractions(loops);
    }

    @Test void cancellationAlsoStopsOwnedChild() {
        run.start(Instant.now()); run.bindLoop(child.getId(), run.getAttempt(), 1);
        when(store.cancel(run.getProjectId(), run.getId())).thenAnswer(call -> { run.cancel(); run.checkpoint(); return run; });
        assertThat(service.cancel(run.getProjectId(), run.getId()).status()).isEqualTo(AutomationStatus.CANCELLED);
        verify(loops).cancel(run.getProjectId(), child.getId());
    }
}
