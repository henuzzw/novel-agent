package com.novelagent.planning.api;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Size;

public record GenerateStoryBibleRequest(
        ModelProvider provider,
        @Size(max = 1000) String instruction,
        GenerationMode mode) {
}
