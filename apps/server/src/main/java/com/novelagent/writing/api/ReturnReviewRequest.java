package com.novelagent.writing.api;

import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import java.util.List;

public record ReturnReviewRequest(ModelProvider provider, GenerationMode mode, List<String> issueIds,
        String instruction) {
}
