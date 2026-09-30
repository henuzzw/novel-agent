package com.novelagent.planning.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record SelectStoryDirectionRequest(@NotNull UUID candidateId) {
}
