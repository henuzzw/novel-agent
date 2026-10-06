package com.novelagent.ingest.api;

import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReversePlanRequest(
        @NotNull ModelProvider provider,
        ImportPlanningMode mode,
        @Size(max = 1000) String instruction,
        @NotNull UUID analysisId,
        @NotNull Long analysisVersion) {

    public ReversePlanRequest(ModelProvider provider, ImportPlanningMode mode, String instruction) {
        this(provider, mode, instruction, null, null);
    }

    public ImportPlanningMode effectiveMode() {
        return mode == null ? ImportPlanningMode.CONTINUE_MANUSCRIPT : mode;
    }
}
