package com.novelagent.writing.api.dto;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.modelaccess.domain.ModelProvider;
import java.util.UUID;

public record GenerateWritingRequest(ModelProvider provider, String instruction, GenerationMode mode,
        UUID baseManuscriptVersionId, UUID baseContractVersionId) {
}
