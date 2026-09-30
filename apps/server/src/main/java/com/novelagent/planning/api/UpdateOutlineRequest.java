package com.novelagent.planning.api;

import com.novelagent.planning.domain.OutlineContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateOutlineRequest(@NotNull @Valid OutlineContent content) {
}
