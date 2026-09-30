package com.novelagent.ingest.api;

import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReversePlanRequest(
        @NotNull ModelProvider provider,
        ImportPlanningMode mode,
        @Size(max = 1000) String instruction) {

    public ImportPlanningMode effectiveMode() {
        return mode == null ? ImportPlanningMode.CONTINUE_MANUSCRIPT : mode;
    }
}
