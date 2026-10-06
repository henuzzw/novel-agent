package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ManuscriptLocalEditRequest(
        @NotNull UUID sourceManuscriptId,
        @NotNull @Min(0) Long sourceRowVersion,
        @NotBlank @Size(max = 12000) String selection,
        @Min(1) Integer occurrence,
        @Min(0) Integer offset,
        @NotNull ModelProvider provider,
        @NotBlank @Size(max = 2000) String instruction,
        boolean authorized) {
}
