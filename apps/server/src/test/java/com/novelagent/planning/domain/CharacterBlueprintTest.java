package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.planning.infrastructure.StoryBibleModelOutputParser;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.application.ModelProvider;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CharacterBlueprintTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void demographicFieldsAreOptionalForLegacyDataAndOnlyFillBlanks() {
        ObjectNode legacy = json.valueToTree(CharacterBlueprintFixtures.character("江澈"));
        legacy.remove(List.of("gender", "ageDescription"));
        var original = json.convertValue(legacy, CharacterBlueprint.class);
        assertThat(original.gender()).isEmpty();
        assertThat(original.ageDescription()).isEmpty();
        legacy.put("gender", "男").put("ageDescription", "16岁");
        var proposed = json.convertValue(legacy, CharacterBlueprint.class);
        var filled = original.fillMissingFrom(proposed);
        assertThat(filled.gender()).isEqualTo("男");
        assertThat(filled.ageDescription()).isEqualTo("16岁");
        legacy.put("gender", "女").put("ageDescription", "17岁");
        assertThat(filled.fillMissingFrom(json.convertValue(legacy, CharacterBlueprint.class))).isEqualTo(filled);
        legacy.put("gender", "x".repeat(41));
        assertThatThrownBy(() -> json.convertValue(legacy, CharacterBlueprint.class)).hasMessageContaining("40");
    }

    @Test void readsLegacyBibleWithoutInventingCharactersAndRoundTripsNewFields() throws Exception {
        var current = CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("江澈")));
        ObjectNode legacy = json.valueToTree(current);
        legacy.remove("characterBlueprints");
        assertThat(json.treeToValue(legacy, StoryBibleContent.class).characterBlueprints()).isEmpty();
        assertThat(json.readValue(json.writeValueAsString(current), StoryBibleContent.class)).isEqualTo(current);
    }

    @Test void validatesIdentityRoleDuplicatesAndLimits() {
        var character = CharacterBlueprintFixtures.character("江澈");
        ObjectNode node = json.valueToTree(character);
        node.put("role", "INVALID");
        assertThatThrownBy(() -> json.convertValue(node, CharacterBlueprint.class)).hasMessageContaining("角色类型");
        node.put("role", "PROTAGONIST").put("coreDesire", " ");
        assertThatThrownBy(() -> json.convertValue(node, CharacterBlueprint.class)).hasMessageContaining("核心欲望");
        node.put("coreDesire", "目标").put("background", "x".repeat(3001));
        assertThatThrownBy(() -> json.convertValue(node, CharacterBlueprint.class)).hasMessageContaining("3000");
        assertThatThrownBy(() -> CharacterBlueprintFixtures.bible(List.of(character, character)))
                .hasMessageContaining("重复");
        assertThatThrownBy(() -> CharacterBlueprintFixtures.bible(java.util.stream.IntStream.range(0, 13)
                .mapToObj(i -> CharacterBlueprintFixtures.character("角色" + i)).toList())).hasMessageContaining("12");
    }

    @Test void schemaCoversEveryStructuredFieldAndParserPreservesIt() throws Exception {
        var schema = new StoryBibleOutputSchema(json).value();
        var entry = schema.at("/properties/content/properties/characterBlueprints/items");
        List<String> required = json.convertValue(entry.get("required"),
                new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        assertThat(required).containsExactlyInAnyOrderElementsOf(Arrays.stream(CharacterBlueprint.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList());
        assertThat(entry.path("additionalProperties").asBoolean()).isFalse();
        var bible = CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("江澈")));
        var output = json.createObjectNode();
        output.set("content", json.valueToTree(bible));
        output.putArray("changeSummary");
        assertThat(new StoryBibleModelOutputParser(json).parse(ModelProvider.DEEPSEEK, output.toString()).content())
                .isEqualTo(bible);
    }

    @Test void copiesListsAndRejectsInvalidEntries() {
        var character = CharacterBlueprintFixtures.character("江澈");
        assertThatThrownBy(() -> character.initialPossessions().add("纸条")).isInstanceOf(UnsupportedOperationException.class);
        ObjectNode node = json.valueToTree(character);
        node.putArray("initialPossessions").add(" ");
        assertThatThrownBy(() -> json.convertValue(node, CharacterBlueprint.class)).hasMessageContaining("列表项");
    }

    @Test void newModelOutputCannotSilentlyOmitTheMainCharacterBlueprint() {
        var output = json.createObjectNode();
        output.set("content", json.valueToTree(CharacterBlueprintFixtures.bible(List.of())));
        output.putArray("changeSummary");
        assertThatThrownBy(() -> new StoryBibleModelOutputParser(json).parse(ModelProvider.DEEPSEEK, output.toString()))
                .hasMessageContaining("格式不合法").hasRootCauseMessage("模型输出缺少主角人物底稿");
    }
}
