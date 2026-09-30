package com.novelagent.planning.api;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Size;

public record GenerateStoryDirectionsRequest(
        @Size(max = 1000) String instruction,
        ModelProvider provider,
        GenerationMode mode) {
}
