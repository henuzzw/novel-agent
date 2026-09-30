package com.novelagent.canon.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record EntityStateResponse(
        UUID changeId,
        String field,
        JsonNode value,
        int chapterNumber,
        long canonVersionFrom,
        String evidence) {
}
