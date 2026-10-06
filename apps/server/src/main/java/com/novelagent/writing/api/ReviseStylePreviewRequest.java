package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReviseStylePreviewRequest(
        @NotNull ModelProvider provider,
        @NotNull @Size(min = 1, max = 20) List<String> issueIds,
        @Size(max = 1000) String instruction) { }
