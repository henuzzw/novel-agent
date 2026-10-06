package com.novelagent.planning.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class CharacterBlueprintCompletion {
    private CharacterBlueprintCompletion() { }

    public static StoryBibleContent merge(StoryBibleContent source, StoryBibleContent renderedSource,
            List<CharacterBlueprint> proposed) {
        if (proposed == null || proposed.isEmpty() || proposed.size() > 12) {
            throw new IllegalArgumentException("模型未返回有效人物底稿");
        }
        if (source.characterBlueprints().size() != renderedSource.characterBlueprints().size()) {
            throw new IllegalArgumentException("人物底稿姓名渲染来源不一致");
        }
        var pending = new LinkedHashMap<String, CharacterBlueprint>();
        for (var character : proposed) {
            if (pending.putIfAbsent(character.name(), character) != null) {
                throw new IllegalArgumentException("模型返回重复人物底稿");
            }
        }
        var result = new ArrayList<CharacterBlueprint>();
        for (int i = 0; i < source.characterBlueprints().size(); i++) {
            var original = source.characterBlueprints().get(i);
            var replacement = pending.remove(renderedSource.characterBlueprints().get(i).name());
            if (!original.name().equals(renderedSource.characterBlueprints().get(i).name())) {
                var formerName = pending.remove(original.name());
                if (replacement != null && formerName != null) {
                    throw new IllegalArgumentException("模型使用当前姓名和旧名重复创建人物底稿");
                }
                if (replacement == null) replacement = formerName;
            }
            result.add(replacement == null ? original : original.fillMissingFrom(replacement));
        }
        result.addAll(pending.values());
        return new StoryBibleContent(source.logline(), source.theme(), source.worldSetting(), source.worldRules(),
                source.protagonist(), source.protagonistArc(), source.supportingCharacters(),
                source.relationshipDynamics(), source.centralConflict(), source.stakes(), source.narrativeStyle(),
                source.endingDirection(), source.hardConstraints(), source.openQuestions(), result, source.readerExperiencePlans());
    }
}
