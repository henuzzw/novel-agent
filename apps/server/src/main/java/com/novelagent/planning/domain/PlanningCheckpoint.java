package com.novelagent.planning.domain;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PlanningCheckpoint(UUID id, UUID projectId, String chunkKey, int chapterFrom, int chapterTo,
        Source source, Status status, long attempt, long version, PlanningCheckpointResult result,
        String failure, Instant createdAt, Instant updatedAt) {
    public static final String SCHEMA_VERSION = "planning-checkpoint/2";

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public record Source(UUID bibleId, long bibleRowVersion, CreativeStrategyPolicy creativeStrategy,
            ModelProvider provider, String instruction, String dependencyHash, List<Dependency> dependencies) {
        public Source {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public Source(UUID bibleId, long bibleRowVersion, CreativeStrategyPolicy creativeStrategy,
                ModelProvider provider, String instruction, String dependencyHash) {
            this(bibleId, bibleRowVersion, creativeStrategy, provider, instruction, dependencyHash, List.of());
        }
    }

    public record Dependency(UUID checkpointId, long attempt, String dependencyHash, String resultHash) { }
}
