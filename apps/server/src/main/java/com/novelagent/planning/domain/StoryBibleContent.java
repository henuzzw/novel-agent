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
        List<String> openQuestions,
        List<CharacterBlueprint> characterBlueprints,
        List<ReaderExperienceSeed> readerExperiencePlans) {

    public StoryBibleContent {
        readerExperiencePlans = validatedPlans(readerExperiencePlans);
        characterBlueprints = characterBlueprints == null ? List.of() : List.copyOf(characterBlueprints);
        if (characterBlueprints.size() > 12) throw new IllegalArgumentException("人物底稿不能超过 12 个角色");
        if (characterBlueprints.stream().map(CharacterBlueprint::name).distinct().count() != characterBlueprints.size()) {
            throw new IllegalArgumentException("人物底稿姓名不能重复，请用身份区分同名人物");
        }
    }

    static List<ReaderExperienceSeed> validatedPlans(List<ReaderExperienceSeed> values) {
        var plans = values == null ? List.<ReaderExperienceSeed>of() : List.copyOf(values);
        if (plans.size() > 80 || plans.stream().map(ReaderExperienceSeed::key).distinct().count() != plans.size()) {
            throw new IllegalArgumentException("承诺与伏笔规划过多或标识重复");
        }
        return plans;
    }

    public StoryBibleContent(String logline, String theme, String worldSetting, List<String> worldRules,
            String protagonist, String protagonistArc, List<String> supportingCharacters,
            List<String> relationshipDynamics, String centralConflict, String stakes, String narrativeStyle,
            String endingDirection, List<String> hardConstraints, List<String> openQuestions,
            List<CharacterBlueprint> characterBlueprints) {
        this(logline, theme, worldSetting, worldRules, protagonist, protagonistArc, supportingCharacters,
                relationshipDynamics, centralConflict, stakes, narrativeStyle, endingDirection,
                hardConstraints, openQuestions, characterBlueprints, List.of());
    }

    public StoryBibleContent(String logline, String theme, String worldSetting, List<String> worldRules,
            String protagonist, String protagonistArc, List<String> supportingCharacters,
            List<String> relationshipDynamics, String centralConflict, String stakes, String narrativeStyle,
            String endingDirection, List<String> hardConstraints, List<String> openQuestions) {
        this(logline, theme, worldSetting, worldRules, protagonist, protagonistArc, supportingCharacters,
                relationshipDynamics, centralConflict, stakes, narrativeStyle, endingDirection,
                hardConstraints, openQuestions, List.of());
    }
}
