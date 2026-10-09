package com.novelagent.project.api;

import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.CreativeStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateProjectRequest(
        @Size(max = 200) String name,
        @NotNull EntryMode entryMode,
        @Valid CreativeIntentRequest creativeIntent,
        CreativeStrategy creativeStrategy) {
}
