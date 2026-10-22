package com.novelagent.planning.api.dto;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.modelaccess.domain.ModelProvider;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record GenerateOutlineRequest(ModelProvider provider, @Size(max = 1000) String instruction,
        GenerationMode mode, UUID baseOutlineVersionId) {
}
