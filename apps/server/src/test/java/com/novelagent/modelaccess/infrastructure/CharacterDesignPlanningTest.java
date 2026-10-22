package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.CharacterDesignService;
import com.novelagent.modelaccess.domain.ModelProvider;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CharacterDesignPlanningTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final CharacterDesignService service = new CharacterDesignService(models, mapper,
            new StoryBibleOutputSchema(mapper));

    @Test void stepSevenUsesOneSharedRequestAndThePlainTextSavingProtocol() throws Exception {
        UUID project = UUID.randomUUID();
        String output = """
                {"materials":{"logline":"共同参赛","theme":"成长","worldSetting":"校园","worldRules":["不得替队友选择"],
                "protagonist":"陆舟","protagonistArc":"学会回应","supportingCharacters":[],"relationshipDynamics":["合作同学"],
                "centralConflict":"分工冲突","stakes":"信任","narrativeStyle":"清爽","endingDirection":"共同选择",
                "hardConstraints":[],"openQuestions":[],"readerExperiencePlans":[],"characterBlueprints":[
                {"name":"陆舟","role":"PROTAGONIST","identity":"大学生","coreDesire":"参赛"}]}}
                """;
        when(models.request(eq(project), eq("CHARACTER_DESIGN"), eq(ModelProvider.LOCAL_CODEX), anyString(),
                anyString(), any(JsonNode.class), eq("snowflake_character_materials"), eq(12000), eq(CodexSessionPolicy.REUSE_THREAD)))
                .thenReturn(output);
        var materials = service.designPlanning(project, ModelProvider.LOCAL_CODEX, "前6步完整底稿", "冻结的创作边界");
        assertThat(materials.characterBlueprints()).extracting(value -> value.name()).containsExactly("陆舟");
        assertThat(materials.worldRules()).containsExactly("不得替队友选择");
        var schema = ArgumentCaptor.forClass(JsonNode.class);
        var input = ArgumentCaptor.forClass(String.class);
        verify(models).request(eq(project), eq("CHARACTER_DESIGN"), eq(ModelProvider.LOCAL_CODEX), anyString(),
                input.capture(), schema.capture(), eq("snowflake_character_materials"), eq(12000), eq(CodexSessionPolicy.REUSE_THREAD));
        assertThat(mapper.readTree(input.getValue()).path("creativeData").asText()).isEqualTo("前6步完整底稿");
        assertThat(mapper.readTree(input.getValue()).path("creativeBoundarySnapshot").asText()).isEqualTo("冻结的创作边界");
        var protocol = new PlainTextOutputProtocol(schema.getValue());
        assertThat(protocol.instructions()).contains("## @materials/characterBlueprints/*/name");
        verifyNoMoreInteractions(models);
    }

    @Test void emptyProjectionAndFakeProviderAreRejected() {
        UUID project = UUID.randomUUID();
        when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{}");
        assertThatThrownBy(() -> service.designPlanning(project, ModelProvider.DEEPSEEK, "底稿", "边界"))
                .hasMessageContaining("没有返回完整资料");
        assertThatThrownBy(() -> service.designPlanning(project, ModelProvider.LOCAL_TEMPLATE, "底稿", "边界"))
                .hasMessageContaining("真实模型");
    }
}
