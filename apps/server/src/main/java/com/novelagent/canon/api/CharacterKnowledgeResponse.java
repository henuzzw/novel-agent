package com.novelagent.canon.api;

import java.util.UUID;

public record CharacterKnowledgeResponse(
        UUID id,
        UUID characterId,
        String characterName,
        UUID factId,
        String subject,
        String predicate,
        String object,
        String knowledgeType,
        String beliefTruth,
        Double confidence,
        int chapterNumber,
        long canonVersionFrom,
        String evidence) {
}
