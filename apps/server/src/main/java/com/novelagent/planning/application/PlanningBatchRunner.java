package com.novelagent.planning.application;

import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PlanningBatchRunner {
    private final PlanningBatchService batches;
    private final PlanningCheckpointRunner checkpoints;

    public PlanningBatchRunner(PlanningBatchService batches, PlanningCheckpointRunner checkpoints) {
        this.batches = batches;
        this.checkpoints = checkpoints;
    }

    public PlanningBatchService.View runNext(UUID projectId, UUID id, long version) {
        var claim = batches.claimNext(projectId, id, version);
        try {
            checkpoints.run(projectId, claim.checkpoint().id(), claim.checkpoint().version(),
                    claim.wordBudget(), claim.chapterCount());
            return batches.finish(projectId, id, claim.batchVersion());
        } catch (RuntimeException failure) {
            try {
                batches.fail(projectId, id, claim.batchVersion());
            } catch (RuntimeException stale) {
                failure.addSuppressed(stale);
            }
            throw failure;
        }
    }
}
