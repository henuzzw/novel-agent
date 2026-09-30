package com.novelagent.writing.domain;

import java.util.List;

public record TypedFactPayload(
        String entityType,
        String entityName,
        String eventTitle,
        String eventSummary,
        String storyTime,
        List<String> participants,
        String fieldKey,
        String beforeValue,
        String afterValue,
        String sourceEntityName,
        String targetEntityName,
        String relationType,
        String characterName,
        String knowledgeType,
        String beliefTruth,
        String statement,
        String foreshadowTitle,
        String targetEffect,
        String foreshadowStatus,
        Integer plannedResolveChapter,
        String entityId,
        String sourceEntityId,
        String targetEntityId,
        String characterId,
        String stateEntityType) {
}
