package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CharacterDesignServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final CharacterDesignService service = new CharacterDesignService(models, mapper, new StoryBibleOutputSchema(mapper), new com.novelagent.planning.infrastructure.FreeTextPlanningRequest(models, mapper));
    private final UUID project = UUID.randomUUID();

    @Test void usesOneAuditedAgentForBothProvidersAndReturnsOnlyValidatedBlueprints() throws Exception {
        var characters = List.of(CharacterBlueprintFixtures.character("江澈"));
        var output = mapper.createObjectNode().set("characterBlueprints", mapper.valueToTree(characters));
        var input = mapper.createObjectNode().put("mode", "CONTINUE_MANUSCRIPT");
        for (var provider : List.of(ModelProvider.LOCAL_CODEX, ModelProvider.DEEPSEEK)) {
            when(models.request(eq(project), eq("CHARACTER_DESIGN"), eq(provider), anyString(), anyString(),
                    any(), eq("character_design"), eq(10000), eq(CodexSessionPolicy.NEW_THREAD))).thenReturn(output.toString());
            assertThat(service.design(project, provider, input)).containsExactlyElementsOf(characters);
            verify(models).request(eq(project), eq("CHARACTER_DESIGN"), eq(provider),
                    eq(AgentPromptDefaults.system("CHARACTER_DESIGN")), eq(input.toString()), any(),
                    eq("character_design"), eq(10000), eq(CodexSessionPolicy.NEW_THREAD));
        }
    }

    @Test void rejectsEmptyDuplicateAndUnexpectedOutputsInsteadOfCreatingFakeCharacters() throws Exception {
        var character = CharacterBlueprintFixtures.character("江澈");
        var duplicate = mapper.createObjectNode().set("characterBlueprints", mapper.valueToTree(List.of(character, character)));
        for (String raw : List.of("{\"characterBlueprints\":[]}", duplicate.toString(), "{\"characters\":[]}", "[]", "null", "not json")) {
            when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any())).thenReturn(raw);
            assertThatThrownBy(() -> service.design(project, ModelProvider.DEEPSEEK, mapper.createObjectNode()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void rejectsTemplateAndNullProviderWithoutCallingGateway() {
        assertThatThrownBy(() -> service.design(project, ModelProvider.LOCAL_TEMPLATE, mapper.createObjectNode())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.design(project, null, mapper.createObjectNode())).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(models);
    }
}
