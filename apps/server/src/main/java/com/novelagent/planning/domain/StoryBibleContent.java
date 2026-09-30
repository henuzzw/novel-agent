package com.novelagent.planning.domain;

import java.util.List;

public record StoryBibleContent(
        String logline,
        String theme,
        String worldSetting,
        List<String> worldRules,
        String protagonist,
        String protagonistArc,
        List<String> supportingCharacters,
        List<String> relationshipDynamics,
        String centralConflict,
        String stakes,
        String narrativeStyle,
        String endingDirection,
        List<String> hardConstraints,
        List<String> openQuestions) {
}
