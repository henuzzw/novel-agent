package com.novelagent.prompt.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class AgentPromptDefaultsTest {
    private final AgentPromptCatalog catalog = new AgentPromptCatalog();

    @Test void activeTemplatesHaveBundledNonemptyResourcesWithinTheLengthLimit() {
        for (var definition : catalog.all()) {
            String path = "/prompts/" + definition.key().toLowerCase(Locale.ROOT) + ".txt";
            assertThat(AgentPromptDefaults.class.getResource(path)).as(definition.key()).isNotNull();
            assertThat(definition.defaultSystemPrompt()).as(definition.key()).isNotBlank();
            assertThat(definition.defaultSystemPrompt().length()).isLessThan(AgentPromptService.MAX_LENGTH);
        }
    }

    @Test void retiredStagesCannotBeConfiguredOrExecuted() {
        assertThat(catalog.all()).noneMatch(value -> value.key().startsWith("CREATION_PREPARATION"));
        for (String key : List.of("CREATION_PREPARATION_WORLD", "CREATION_PREPARATION_PLOT", "CREATION_PREPARATION_REVIEW",
                "CHAPTER_CONTRACT", "CHAPTER_CONTRACT_REVIEW", "CHARACTER_BLUEPRINT_COMPLETION", "STORY_BIBLE",
                "IMPORT_REVERSE_BIBLE_ADAPT", "IMPORT_REVERSE_BIBLE_CONTINUE")) {
            assertThatThrownBy(() -> catalog.require(key)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> AgentPromptDefaults.system(key)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void unknownTemplatesCannotReadOtherResourcesOrReactivateRetiredStages() {
        for (String key : new String[] { "NOT_AN_AGENT", "../application", "manuscript", "" }) {
            assertThatThrownBy(() -> AgentPromptDefaults.system(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AgentPromptDefaults.system(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.all()).noneMatch(value -> value.key().equals("CHAPTER_CONTRACT"));
    }
}
