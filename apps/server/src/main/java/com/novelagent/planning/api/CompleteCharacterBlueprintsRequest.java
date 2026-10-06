package com.novelagent.planning.api;

import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CompleteCharacterBlueprintsRequest(
        @NotNull ModelProvider provider,
        @Size(max = 1000) String instruction) { }
