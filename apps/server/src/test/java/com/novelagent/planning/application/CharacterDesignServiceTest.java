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

    @Test void promptKeepsTheThreeDrivesArcAndPermissionModesTogether() {
        assertThat(AgentPromptDefaults.system("CHARACTER_DESIGN")).contains("表面追求", "深层渴望", "灵魂需求",
                "初始状态 → 触发事件 → 认知失调 → 蜕变节点 → 最终状态", "至少两人的价值冲突",
                "合作纽带", "潜在背叛", "CONTINUE_MANUSCRIPT", "ADAPT_SOURCE", "COMPLETE_MISSING", "REVISE_AUTHORIZED",
                "不为数量造人", "不把弧光、秘密、背叛设计成既往事实", "只补空白", "不解释");
    }

    @Test void separatesCreativePermissionFromEvidenceExtractionWithoutBlanketBan() {
        String prompt = AgentPromptDefaults.system("CHARACTER_DESIGN");
        assertThat(prompt).contains("NEW_STORY：这是创作设计", "ADAPT_SOURCE：按作者确认的 KEEP/REWORK/DROP",
                "具体姓名", "不把合法的新设计一律留空", "明确属于新增设计", "作者明确要求匿名",
                "具体经历 → 形成的应对方式", "主线或爱情之外的生活目标", "不限制为一两句",
                "不为旧人物补造过去", "COMPLETE_MISSING 和准备模式不获得全面重设计权限");
        assertThat(prompt).doesNotContain("未知留空或提出待确认问题，不编造依据。",
                "未知或待作者确认的信息不得擅自坐实，不为修补剧情新增能力、经历、道具或秘密。");
        assertThat(com.novelagent.prompt.application.AgentPromptService.PROTECTED_RULES)
                .contains("仅当本次任务明确为原创人物设计或授权素材改编时", "不能借此扩大其他阶段职责",
                        "原文提炼、续写既往事实、审阅和正文修订");
        assertThat(AgentPromptDefaults.system("IMPORT_SOURCE_ANALYSIS"))
                .contains("不编造设定或未来情节").doesNotContain("这是创作设计");
    }
}
