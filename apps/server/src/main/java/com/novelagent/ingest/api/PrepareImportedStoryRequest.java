package com.novelagent.ingest.api;

import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record PrepareImportedStoryRequest(@NotNull ModelProvider provider, @NotNull ImportPlanningMode mode,
        @Size(max = 1000) String instruction, @NotNull UUID analysisId, @NotNull Long analysisVersion,
        @Min(1000) @Max(10000000) int targetWords, boolean expandScenes) { }
