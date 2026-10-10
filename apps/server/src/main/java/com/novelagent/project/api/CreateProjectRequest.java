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
        CreativeStrategy creativeStrategy,
        com.novelagent.project.domain.CreativeBoundaries.WorldMode worldMode,
        com.novelagent.project.domain.CreativeBoundaries.AdaptationScope adaptationScope) {
    public CreateProjectRequest(String name, EntryMode entryMode, CreativeIntentRequest creativeIntent, CreativeStrategy creativeStrategy) {
        this(name, entryMode, creativeIntent, creativeStrategy, null, null);
    }
}
