package com.novelagent.canon.api;

import java.util.UUID;

public record CharacterProfileResponse(
        UUID characterId,
        String roleKey,
        String canonicalName,
        String gender,
        String ageDescription,
        String identity,
        String appearance,
        String background,
        String externalPersonality,
        String internalPersonality,
        String coreDesire,
        String fear,
        String flaw,
        String values,
        String speechStyle,
        String behaviorHabits,
        String secret,
        String characterArc,
        String behaviorBoundaries,
        String notes,
        long version) {
}
