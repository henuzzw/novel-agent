package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.GenerationMode;
import java.util.UUID;

public record GenerateWritingRequest(ModelProvider provider, String instruction, GenerationMode mode,
        UUID baseManuscriptVersionId, UUID baseContractVersionId) {
}
