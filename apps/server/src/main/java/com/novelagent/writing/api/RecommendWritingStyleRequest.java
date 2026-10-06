package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RecommendWritingStyleRequest(
        @NotNull UUID bibleVersionId,
        @NotNull @Min(0) Long expectedBibleVersion,
        @NotNull ModelProvider provider,
        @Size(max = 1000) String instruction) {
}
