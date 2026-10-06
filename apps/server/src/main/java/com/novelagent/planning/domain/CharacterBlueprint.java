package com.novelagent.planning.domain;

import java.util.List;
import java.util.Set;

public record CharacterBlueprint(
        String name, String role, String identity, String appearance, String background,
        String externalPersonality, String internalPersonality, String coreDesire,
        String fear, String flaw, String values, String speechStyle, String behaviorHabits,
        String abilitiesAndLimits, String behaviorBoundaries, String secret, String characterArc,
        String openingState, List<String> initialRelationships, List<String> initialPossessions,
        List<String> knowledgeBoundaries, String gender, String ageDescription) {

    public CharacterBlueprint(String name, String role, String identity, String appearance, String background,
            String externalPersonality, String internalPersonality, String coreDesire, String fear, String flaw,
            String values, String speechStyle, String behaviorHabits, String abilitiesAndLimits,
            String behaviorBoundaries, String secret, String characterArc, String openingState,
            List<String> initialRelationships, List<String> initialPossessions, List<String> knowledgeBoundaries) {
        this(name, role, identity, appearance, background, externalPersonality, internalPersonality, coreDesire,
                fear, flaw, values, speechStyle, behaviorHabits, abilitiesAndLimits, behaviorBoundaries, secret,
                characterArc, openingState, initialRelationships, initialPossessions, knowledgeBoundaries, "", "");
    }

    public CharacterBlueprint {
        name = required(name, "姓名", 100);
        if (!Set.of("PROTAGONIST", "SUPPORTING", "MINOR").contains(role == null ? "" : role)) {
            throw new IllegalArgumentException("人物底稿角色类型无效");
        }
        identity = required(identity, "身份", 3000);
        coreDesire = required(coreDesire, "核心欲望", 3000);
        appearance = optional(appearance);
        background = optional(background);
        externalPersonality = optional(externalPersonality);
        internalPersonality = optional(internalPersonality);
        fear = optional(fear);
        flaw = optional(flaw);
        values = optional(values);
        speechStyle = optional(speechStyle);
        behaviorHabits = optional(behaviorHabits);
        abilitiesAndLimits = optional(abilitiesAndLimits);
        behaviorBoundaries = optional(behaviorBoundaries);
        secret = optional(secret);
        characterArc = optional(characterArc);
        openingState = optional(openingState);
        initialRelationships = entries(initialRelationships);
        initialPossessions = entries(initialPossessions);
        knowledgeBoundaries = entries(knowledgeBoundaries);
        gender = boundedOptional(gender, "性别", 40);
        ageDescription = boundedOptional(ageDescription, "年龄", 100);
    }

    private static String required(String value, String field, int limit) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("人物底稿缺少" + field);
        String result = value.trim();
        if (result.length() > limit) throw new IllegalArgumentException("人物底稿" + field + "过长");
        return result;
    }

    private static String optional(String value) {
        if (value == null) return "";
        if (value.length() > 3000) throw new IllegalArgumentException("人物底稿单项不能超过 3000 字符");
        return value.trim();
    }

    private static List<String> entries(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 20) throw new IllegalArgumentException("人物底稿列表不能超过 20 项");
        return values.stream().map(value -> required(value, "列表项", 1000)).toList();
    }

    public CharacterBlueprint fillMissingFrom(CharacterBlueprint proposed) {
        return new CharacterBlueprint(name, role, identity, fill(appearance, proposed.appearance),
                fill(background, proposed.background), fill(externalPersonality, proposed.externalPersonality),
                fill(internalPersonality, proposed.internalPersonality), coreDesire, fill(fear, proposed.fear),
                fill(flaw, proposed.flaw), fill(values, proposed.values), fill(speechStyle, proposed.speechStyle),
                fill(behaviorHabits, proposed.behaviorHabits), fill(abilitiesAndLimits, proposed.abilitiesAndLimits),
                fill(behaviorBoundaries, proposed.behaviorBoundaries), fill(secret, proposed.secret),
                fill(characterArc, proposed.characterArc), fill(openingState, proposed.openingState),
                initialRelationships.isEmpty() ? proposed.initialRelationships : initialRelationships,
                initialPossessions.isEmpty() ? proposed.initialPossessions : initialPossessions,
                knowledgeBoundaries.isEmpty() ? proposed.knowledgeBoundaries : knowledgeBoundaries,
                fill(gender, proposed.gender), fill(ageDescription, proposed.ageDescription));
    }

    private static String boundedOptional(String value, String field, int limit) {
        String result = value == null ? "" : value.trim();
        if (result.length() > limit) throw new IllegalArgumentException("人物底稿" + field + "不能超过 " + limit + " 字符");
        return result;
    }

    private static String fill(String current, String proposed) { return current.isBlank() ? proposed : current; }
}
