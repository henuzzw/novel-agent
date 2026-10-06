package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterBlueprintCompletionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void fillsOnlyBlankFieldsAndKeepsAllOtherBibleContent() {
        var existing = CharacterBlueprintFixtures.character("江澈");
        ObjectNode original = json.valueToTree(existing);
        original.put("background", "");
        original.putArray("initialPossessions");
        var source = CharacterBlueprintFixtures.bible(List.of(json.convertValue(original, CharacterBlueprint.class)));
        ObjectNode proposed = json.valueToTree(existing);
        proposed.put("coreDesire", "模型试图改变欲望").put("identity", "模型试图改变身份");
        proposed.put("secret", "新增偷拍秘密");

        var merged = CharacterBlueprintCompletion.merge(source, source,
                List.of(json.convertValue(proposed, CharacterBlueprint.class)));

        assertThat(merged.characterBlueprints().getFirst().background()).isEqualTo(existing.background());
        assertThat(merged.characterBlueprints().getFirst().initialPossessions()).isEqualTo(existing.initialPossessions());
        assertThat(merged.characterBlueprints().getFirst().identity()).isEqualTo(existing.identity());
        assertThat(merged.characterBlueprints().getFirst().coreDesire()).isEqualTo(existing.coreDesire());
        assertThat(merged.characterBlueprints().getFirst().secret()).isEqualTo(existing.secret());
        ObjectNode actual = json.valueToTree(merged);
        ObjectNode before = json.valueToTree(source);
        actual.remove("characterBlueprints"); before.remove("characterBlueprints");
        assertThat(actual).isEqualTo(before);
    }

    @Test void matchesRenderedNamesWithoutCreatingDuplicateCharactersAndKeepsOmittedAuthors() {
        var original = CharacterBlueprintFixtures.character("旧名");
        var other = CharacterBlueprintFixtures.character("许冬");
        var source = CharacterBlueprintFixtures.bible(List.of(original, other));
        var rendered = CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("新名"), other));
        var merged = CharacterBlueprintCompletion.merge(source, rendered,
                List.of(CharacterBlueprintFixtures.character("新名")));
        assertThat(merged).isEqualTo(source);
        assertThat(merged.characterBlueprints()).containsExactly(original, other);
    }

    @Test void canAddMissingCharactersButRejectsEmptyAndDuplicateOutput() {
        var source = CharacterBlueprintFixtures.bible(List.of());
        var character = CharacterBlueprintFixtures.character("江澈");
        assertThat(CharacterBlueprintCompletion.merge(source, source, List.of(character)).characterBlueprints())
                .containsExactly(character);
        assertThatThrownBy(() -> CharacterBlueprintCompletion.merge(source, source, List.of()))
                .hasMessageContaining("有效人物底稿");
        assertThatThrownBy(() -> CharacterBlueprintCompletion.merge(source, source, List.of(character, character)))
                .hasMessageContaining("重复");
    }
}
