package com.novelagent.prompt.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 提示词只来自打包的文件：解析不改写阶段指令，只选择会话角色并按文件内容生成版本标识。 */
class AgentPromptServiceTest {
    private final AgentPromptCatalog catalog = new AgentPromptCatalog();
    private final AgentPromptService service = new AgentPromptService(catalog);

    @Test void catalogListsOnlyActiveStagesBackedByFiles() {
        assertThat(catalog.all().stream().map(AgentPromptCatalog.Definition::key))
                .doesNotHaveDuplicates()
                .contains("OUTLINE", "SNOWFLAKE_PLANNING", "CHARACTER_DESIGN", "PROJECT_CHAT", "MANUSCRIPT")
                .doesNotContain("STORY_BIBLE", "IMPORT_REVERSE_BIBLE_ADAPT", "IMPORT_REVERSE_BIBLE_CONTINUE",
                        "CHAPTER_CONTRACT", "CHAPTER_CONTRACT_REVIEW", "CHARACTER_BLUEPRINT_COMPLETION");
        catalog.all().forEach(value -> assertThat(value.defaultSystemPrompt()).isNotBlank());
        assertThatThrownBy(() -> catalog.require("NOT_AN_AGENT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void resolveKeepsRenderedStageTextAndUsesFileBasedRevision() {
        String original = "真实系统文本含动态姓名与人物边界";
        var result = service.resolve("MANUSCRIPT", original);
        assertThat(result.systemPrompt()).isEqualTo(original);
        assertThat(result.revision()).startsWith("MANUSCRIPT:file:").isEqualTo(service.resolve("MANUSCRIPT", "另一段").revision());
        assertThat(result.sessionSystemPrompt()).isEqualTo(AgentPromptDefaults.sessionSystemPrompt());
        assertThat(service.resolve("QUALITY_REVIEW", "检查指令").revision()).isNotEqualTo(result.revision());
    }

    @Test void importModesAreIdentifiedByTheirOwnFilesAndUnknownVariantsAreNotGuessed() {
        String adapt = AgentPromptDefaults.system("IMPORT_REVERSE_OUTLINE_ADAPT");
        String continuation = AgentPromptDefaults.system("IMPORT_REVERSE_OUTLINE_CONTINUE");
        assertThat(service.resolve("IMPORT_REVERSE_OUTLINE", adapt).systemPrompt()).isEqualTo(adapt);
        assertThat(service.resolve("IMPORT_REVERSE_OUTLINE", continuation).revision()).startsWith("planning:");
        assertThat(service.resolve("IMPORT_REVERSE_OUTLINE", "无法确定模式"))
                .isEqualTo(new AgentPromptService.Resolved("无法确定模式", null));
    }

    @Test void planningStagesShareOneConversationRevision() {
        String revision = service.resolve("OUTLINE", "大纲").revision();
        assertThat(revision).startsWith("planning:");
        assertThat(service.resolve("SNOWFLAKE_PLANNING", "雪花规划").revision()).isEqualTo(revision);
        assertThat(service.resolve("CHARACTER_DESIGN", "人物").revision()).isEqualTo(revision);
    }

    @Test void stageSpecificSessionRolesComeFromTheirFiles() {
        assertThat(service.resolve("SNOWFLAKE_PLANNING", "阶段").sessionSystemPrompt())
                .isEqualTo(AgentPromptDefaults.system("SNOWFLAKE_SYSTEM"));
        assertThat(service.resolve("PROJECT_CHAT", "对话").sessionSystemPrompt())
                .isEqualTo(AgentPromptDefaults.system("PROJECT_CHAT_SYSTEM"));
        assertThat(service.resolve("STORY_BIBLE", "已退休阶段").sessionSystemPrompt())
                .isEqualTo(AgentPromptDefaults.sessionSystemPrompt());
    }

    @Test void protectedRulesAddStageSpecificBoundaries() {
        assertThat(AgentPromptService.protectedRules("MANUSCRIPT")).contains("不提交正史").doesNotContain("C 只裁决");
        assertThat(AgentPromptService.protectedRules("DRAFT_JUDGE_REVISION")).contains("C 只裁决当前 B 意见");
        assertThat(AgentPromptService.protectedRules("CHAPTER_REVIEW")).contains("发布后记忆整理只读取");
    }
}
