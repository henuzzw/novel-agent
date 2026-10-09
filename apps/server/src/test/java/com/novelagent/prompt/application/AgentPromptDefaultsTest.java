package com.novelagent.prompt.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test void retiredPreparationCannotBeConfiguredOrExecuted() {
        assertThat(catalog.all()).noneMatch(value -> value.key().startsWith("CREATION_PREPARATION"));
        assertThatThrownBy(() -> AgentPromptDefaults.system("CREATION_PREPARATION_PLOT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentPromptDefaults.system("CREATION_PREPARATION_REVIEW"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void editorialAliasesUseTheSameActiveTemplates() {
        assertThat(DraftEditorialPrompts.WRITE).isEqualTo(AgentPromptDefaults.system("MANUSCRIPT"));
        assertThat(DraftEditorialPrompts.CHECK).isEqualTo(AgentPromptDefaults.system("QUALITY_REVIEW"));
        assertThat(DraftEditorialPrompts.JUDGE).isEqualTo(AgentPromptDefaults.system("DRAFT_JUDGE_REVISION"));
    }

    @Test void unknownTemplatesCannotReadOtherResourcesOrReactivateRetiredStages() {
        for (String key : new String[] { "NOT_AN_AGENT", "../application", "manuscript", "" }) {
            assertThatThrownBy(() -> AgentPromptDefaults.system(key)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> AgentPromptDefaults.system(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(catalog.all()).noneMatch(value -> value.key().equals("CHAPTER_CONTRACT"));
    }
}
