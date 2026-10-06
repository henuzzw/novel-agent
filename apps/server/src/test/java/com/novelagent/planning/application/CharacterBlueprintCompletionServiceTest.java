package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CharacterBlueprintCompletionServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID bibleId = UUID.randomUUID();
    private final ObjectMapper json = new ObjectMapper();
    private final CharacterBlueprintDraftStore drafts = mock(CharacterBlueprintDraftStore.class);
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final CharacterBlueprintCompletionService service = new CharacterBlueprintCompletionService(
            drafts, models, json, new StoryBibleOutputSchema(json));

    @Test void callsOneAuditedNewSessionOutsideTransactionsThenSavesCandidate() throws Exception {
        var bible = CharacterBlueprintFixtures.bible(List.of());
        var source = new CharacterBlueprintDraftStore.Source(projectId, bibleId, 2, bible, bible);
        var character = CharacterBlueprintFixtures.character("江澈");
        when(drafts.load(projectId, bibleId, 2)).thenReturn(source);
        var output = json.createObjectNode(); output.set("characterBlueprints", json.valueToTree(List.of(character)));
        when(models.request(eq(projectId), eq("CHARACTER_BLUEPRINT_COMPLETION"), eq(ModelProvider.DEEPSEEK),
                anyString(), anyString(), any(), eq("character_blueprint_completion"), eq(8000), eq(CodexSessionPolicy.NEW_THREAD)))
                .thenReturn(output.toString());
        service.complete(projectId, bibleId, 2, ModelProvider.DEEPSEEK, "不新增转学");
        var input = ArgumentCaptor.forClass(String.class);
        var schema = ArgumentCaptor.forClass(JsonNode.class);
        verify(models).request(eq(projectId), eq("CHARACTER_BLUEPRINT_COMPLETION"), eq(ModelProvider.DEEPSEEK),
                anyString(), input.capture(), schema.capture(), anyString(), eq(8000), eq(CodexSessionPolicy.NEW_THREAD));
        assertThat(json.readTree(input.getValue()).get("storyBible")).isEqualTo(json.valueToTree(bible));
        assertThat(schema.getValue().at("/properties/characterBlueprints/items/properties/knowledgeBoundaries").isObject()).isTrue();
        verify(drafts).save(source, List.of(character), ModelProvider.DEEPSEEK, "不新增转学");
        assertThat(CharacterBlueprintCompletionService.class.getMethod("complete", UUID.class, UUID.class, long.class,
                ModelProvider.class, String.class).getAnnotation(org.springframework.transaction.annotation.Transactional.class)).isNull();
    }

    @Test void rejectsLocalTemplateBeforeLoadingOrCallingModel() {
        assertThatThrownBy(() -> service.complete(projectId, bibleId, 0, ModelProvider.LOCAL_TEMPLATE, null))
                .hasMessageContaining("真实模型");
        verify(drafts, never()).load(any(), any(), anyInt());
        verify(models, never()).request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any());
    }

    @Test void invalidOutputDoesNotSaveOrAutomaticallyRetry() {
        var bible = CharacterBlueprintFixtures.bible(List.of());
        when(drafts.load(projectId, bibleId, 0))
                .thenReturn(new CharacterBlueprintDraftStore.Source(projectId, bibleId, 0, bible, bible));
        when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{\"characterBlueprints\":[],\"logline\":\"偷偷改故事\"}");
        assertThatThrownBy(() -> service.complete(projectId, bibleId, 0, ModelProvider.LOCAL_CODEX, null))
                .hasMessageContaining("结构不合法");
        verify(drafts, never()).save(any(), any(), any(), any());
        verify(models).request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any());
    }
}
