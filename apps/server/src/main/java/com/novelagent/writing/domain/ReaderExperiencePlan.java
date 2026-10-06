package com.novelagent.writing.domain;

import java.time.Instant;
import java.util.UUID;

public record ReaderExperiencePlan(UUID id, UUID projectId, Kind kind, String title, String promise,
        String setup, String payoff, String aftermath, Integer plannedChapter, long version,
        String schemaVersion, boolean deleted, Instant createdAt, Instant updatedAt) {
    public enum Kind { PROMISE, FORESHADOW }
}
