package com.novelagent.project.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreativeIntentRequest(
        @Size(max = 2000) String premise,
        @Size(max = 10) List<@Size(max = 50) String> genres,
        @Size(max = 300) String targetAudience,
        @Size(max = 2000) String protagonistBrief,
        @Size(max = 2000) String centralConflict,
        @Size(max = 10) List<@Size(max = 50) String> tones,
        @Min(1000) @Max(10_000_000) Integer targetWords,
        @Size(max = 300) String endingPreference,
        @Size(max = 30) List<@Size(max = 300) String> mustHave,
        @Size(max = 30) List<@Size(max = 300) String> avoid,
        @Size(max = 30) List<@Size(max = 300) String> stylePreferences) {
}

