package com.novelagent.writing.api.dto;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.modelaccess.domain.ModelProvider;
import java.util.List;

public record ReturnReviewRequest(ModelProvider provider, GenerationMode mode, List<String> issueIds,
        String instruction) {
}
