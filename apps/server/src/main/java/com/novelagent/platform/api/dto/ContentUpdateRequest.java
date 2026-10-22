package com.novelagent.platform.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Shared content envelope; each endpoint decides whether to enable bean validation. */
public record ContentUpdateRequest<T>(@NotNull @Valid T content) {
}
