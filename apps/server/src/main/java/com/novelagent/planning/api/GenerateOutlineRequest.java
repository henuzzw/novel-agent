package com.novelagent.planning.api;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record GenerateOutlineRequest(ModelProvider provider, @Size(max = 1000) String instruction,
        GenerationMode mode, UUID baseOutlineVersionId) {
}
