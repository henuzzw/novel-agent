package com.novelagent.planning.domain;

import com.novelagent.planning.application.ModelProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningBatch(UUID id, UUID projectId, UUID requestId, Source source, String sourceHash,
        List<UUID> checkpointIds, Status status, UUID outlineVersionId, long version, Instant createdAt) {
    public PlanningBatch {
        checkpointIds = List.copyOf(checkpointIds);
    }

    public enum Status { READY, RUNNING, FAILED, CANCELLED, SUCCEEDED }

    public record Source(UUID bibleId, long bibleRowVersion, int chapterTo, int chunkSize,
            ModelProvider provider, String instruction, OutlineWordBudget wordBudget) { }
}
