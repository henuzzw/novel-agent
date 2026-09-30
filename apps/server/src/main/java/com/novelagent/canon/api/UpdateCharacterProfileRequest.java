package com.novelagent.canon.api;

import jakarta.validation.constraints.Size;

public record UpdateCharacterProfileRequest(
        @Size(max = 40) String gender,
        @Size(max = 100) String ageDescription,
        @Size(max = 2000) String identity,
        @Size(max = 3000) String appearance,
        @Size(max = 6000) String background,
        @Size(max = 4000) String externalPersonality,
        @Size(max = 4000) String internalPersonality,
        @Size(max = 3000) String coreDesire,
        @Size(max = 3000) String fear,
        @Size(max = 3000) String flaw,
        @Size(max = 3000) String values,
        @Size(max = 4000) String speechStyle,
        @Size(max = 4000) String behaviorHabits,
        @Size(max = 5000) String secret,
        @Size(max = 5000) String characterArc,
        @Size(max = 5000) String behaviorBoundaries,
        @Size(max = 5000) String notes) {
}
