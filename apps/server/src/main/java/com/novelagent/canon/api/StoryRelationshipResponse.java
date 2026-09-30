package com.novelagent.canon.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

public record StoryRelationshipResponse(
        UUID id,
        UUID sourceEntityId,
        String sourceEntityName,
        UUID targetEntityId,
        String targetEntityName,
        String relationType,
        JsonNode attributes,
        int chapterNumber,
        long canonVersionFrom,
        String evidence) {
}
