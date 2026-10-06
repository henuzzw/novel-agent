package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.planning.domain.PlanningCheckpoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningBatchRunnerTest {
    private final PlanningBatchService batches = mock(PlanningBatchService.class);
    private final PlanningCheckpointRunner checkpoints = mock(PlanningCheckpointRunner.class);
    private final PlanningBatchRunner runner = new PlanningBatchRunner(batches, checkpoints);
    private final UUID project = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final PlanningCheckpoint checkpoint = mock(PlanningCheckpoint.class);

    @Test void oneClickRunsOnlyTheClaimedChunk() {
        when(checkpoint.id()).thenReturn(UUID.randomUUID());
        when(checkpoint.version()).thenReturn(2L);
        when(batches.claimNext(project, id, 3)).thenReturn(new PlanningBatchService.Claim(4, checkpoint, null, 5));
        var view = mock(PlanningBatchService.View.class);
        when(batches.finish(project, id, 4)).thenReturn(view);
        assertThat(runner.runNext(project, id, 3)).isSameAs(view);
        verify(checkpoints).run(project, checkpoint.id(), 2, null, 5);
        verify(batches).finish(project, id, 4);
    }

    @Test void sourceConflictDoesNotStartTheModel() {
        when(batches.claimNext(project, id, 3)).thenThrow(new PlanningCheckpointException("已变化", false));
        assertThatThrownBy(() -> runner.runNext(project, id, 3)).hasMessage("已变化");
        verifyNoInteractions(checkpoints);
    }

    @Test void failureIsRecordedButNotAutomaticallyRetried() {
        when(checkpoint.id()).thenReturn(UUID.randomUUID());
        when(batches.claimNext(project, id, 3)).thenReturn(new PlanningBatchService.Claim(4, checkpoint, null, 5));
        when(checkpoints.run(project, checkpoint.id(), 0, null, 5)).thenThrow(new IllegalStateException("provider"));
        assertThatThrownBy(() -> runner.runNext(project, id, 3)).hasMessage("provider");
        verify(batches).fail(project, id, 4);
        verify(checkpoints).run(project, checkpoint.id(), 0, null, 5);
    }
}
