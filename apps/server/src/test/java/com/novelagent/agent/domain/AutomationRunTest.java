package com.novelagent.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novelagent.planning.application.ModelProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomationRunTest {
    @Test
    void advancesOnlyWhileRunningAndCompletesLastChapter() {
        AutomationRun run = run(1, 2);
        assertThatThrownBy(run::advanceChapter).isInstanceOf(IllegalStateException.class);
        run.start(Instant.now());
        run.advanceChapter();
        assertThat(run.getCurrentChapter()).isEqualTo(2);
        run.advanceChapter();
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.SUCCEEDED);
        assertThatThrownBy(() -> run.start(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void waitsAndResumesWithoutLosingCompletedArtifacts() {
        AutomationRun run = run(1, 1);
        run.start(Instant.now());
        run.beginStep("CONTRACT");
        UUID artifact = UUID.randomUUID();
        run.completeStep(artifact);
        run.waitForUser("确认合同");
        run.start(Instant.now());
        assertThat(run.getAttempt()).isEqualTo(2);
        assertThat(run.getSteps().getFirst().artifactId()).isEqualTo(artifact);
        assertThat(run.getWaitingReason()).isNull();
    }

    @Test
    void recordsFailureAndAllowsExplicitRetry() {
        AutomationRun run = run(1, 1);
        run.start(Instant.now());
        run.beginStep("CONTRACT");
        run.fail("ModelProviderException");
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.FAILED);
        assertThat(run.getSteps().getFirst().errorCode()).isEqualTo("ModelProviderException");
        run.start(Instant.now());
        assertThat(run.getAttempt()).isEqualTo(2);
        assertThat(run.getErrorCode()).isNull();
    }

    @Test
    void cancellationFinishesInFlightArtifactAndStopsAtCheckpoint() {
        AutomationRun run = run(1, 1);
        run.start(Instant.now());
        run.beginStep("MANUSCRIPT");
        run.cancel();
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.RUNNING);
        run.completeStep(UUID.randomUUID());
        assertThat(run.checkpoint()).isFalse();
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.CANCELLED);
        assertThatThrownBy(() -> run.start(Instant.now())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsConcurrentClaimsAndRecoversExpiredAttempt() {
        AutomationRun run = run(1, 1);
        run.start(Instant.now());
        run.beginStep("CONTRACT");
        assertThatThrownBy(() -> run.start(Instant.now())).isInstanceOf(IllegalStateException.class);
        run.start(Instant.now().plus(21, ChronoUnit.MINUTES));
        assertThat(run.getAttempt()).isEqualTo(2);
        assertThat(run.getSteps().getFirst().status()).isEqualTo("FAILED");
    }

    @Test
    void cancelsWaitingTaskImmediatelyAndValidatesChapterLimits() {
        AutomationRun run = run(1, 1);
        run.start(Instant.now());
        run.waitForUser("确认合同");
        run.cancel();
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.CANCELLED);
        assertThatThrownBy(() -> run(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(4, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> run(1, 21)).isInstanceOf(IllegalArgumentException.class);
        assertThat(run(1, 20).getLastChapter()).isEqualTo(20);
    }

    private static AutomationRun run(int first, int last) {
        return AutomationRun.create(UUID.randomUUID(), UUID.randomUUID(), first, last, ModelProvider.LOCAL_TEMPLATE, null);
    }

    @Test void generationLimitCountsFailedAndInterruptedWorkAcrossResumes() {
        var run = limited(1, 2);
        run.start(Instant.now());
        run.beginStep("CONTRACT");
        run.fail("FAILURE");
        run.start(Instant.now());
        run.beginStep("CONTRACT");
        run.start(Instant.now().plus(21, ChronoUnit.MINUTES));
        run.beginStep("CONTRACT");
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(run.getUsedGenerationSteps()).isEqualTo(2);
        assertThat(run.getWaitingReason()).contains("上限", "不会重置");
        run.start(Instant.now());
        run.beginStep("QUALITY_REVIEW");
        assertThat(run.getUsedGenerationSteps()).isEqualTo(2);
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
    }

    @Test void revisionLimitCountsFailuresPerChapterAndRequiresRecheckCapacity() {
        var run = limited(1, 10);
        run.start(Instant.now());
        run.beginStep("QUALITY_REVISION");
        run.fail("MODEL_FAILURE");
        run.start(Instant.now());
        run.beginStep("QUALITY_REVISION");
        assertThat(run.getStatus()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(run.getUsedAutoRevisionRounds()).isEqualTo(1);
        run.start(Instant.now());
        run.advanceChapter();
        assertThat(run.getUsedAutoRevisionRounds()).isZero();
        assertThat(run.getUsedGenerationSteps()).isEqualTo(1);
        var insufficient = limited(1, 1);
        insufficient.start(Instant.now());
        insufficient.beginStep("QUALITY_REVISION");
        assertThat(insufficient.getStatus()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
        assertThat(insufficient.getWaitingReason()).contains("复检");
        assertThat(insufficient.getSteps()).isEmpty();
    }

    @Test void validatesOptInAndKeepsLegacyDefaults() {
        assertThat(run(1, 1).getMaxAutoRevisionRounds()).isZero();
        assertThat(run(1, 1).getMaxGenerationSteps()).isEqualTo(100);
        for (int rounds : new int[] {-1, 4}) {
            assertThatThrownBy(() -> limited(rounds, 100)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int limit : new int[] {0, 501}) {
            assertThatThrownBy(() -> limited(1, limit)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AutomationRun.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 1, ModelProvider.LOCAL_TEMPLATE, null, true, 1, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomationRun.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 1, ModelProvider.DEEPSEEK, null, false, 1, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    private static AutomationRun limited(int rounds, int limit) {
        return AutomationRun.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                1, 2, ModelProvider.DEEPSEEK, null, true, rounds, limit);
    }
}
