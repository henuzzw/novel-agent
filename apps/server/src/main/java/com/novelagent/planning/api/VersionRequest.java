package com.novelagent.planning.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Expected row version for an explicit planning action. */
public record VersionRequest(@NotNull @PositiveOrZero Long version) {
}
