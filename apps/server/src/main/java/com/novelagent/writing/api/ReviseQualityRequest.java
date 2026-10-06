package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReviseQualityRequest(ModelProvider provider,
        @NotNull @Size(min = 1, max = 20) List<@NotBlank String> issueIds,
        @Size(max = 2000) String instruction, Scope scope) {
    public enum Scope {
        EXPRESSION_ONLY, SCENE_STRUCTURE;

        @JsonCreator
        public static Scope from(String value) { return value == null ? EXPRESSION_ONLY : valueOf(value); }
    }

    public ReviseQualityRequest {
        scope = scope == null ? Scope.EXPRESSION_ONLY : scope;
    }
}
