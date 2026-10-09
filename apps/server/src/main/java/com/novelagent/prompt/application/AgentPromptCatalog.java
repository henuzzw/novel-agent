package com.novelagent.prompt.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 当前工作流的配对提示词目录；导入分别区分改编、续写模板，避免互相覆盖。 */
@Component
public class AgentPromptCatalog {
    private static final Set<String> SHARED_PLANNING = Set.of("BOOK_TITLE", "IMPORT_SOURCE_ANALYSIS",
            "SNOWFLAKE_PLANNING", "CHARACTER_DESIGN", "STORY_DIRECTION", "STORY_BIBLE", "OUTLINE",
            "IMPORT_REVERSE_BIBLE", "IMPORT_REVERSE_OUTLINE", "PLANNING_CHECKPOINT");

    public static boolean sharesConversation(String workflow) { return SHARED_PLANNING.contains(workflow); }
    private final List<Definition> definitions = List.of(
            define("BOOK_TITLE", "自动书名", "导入"),
            define("STORY_DIRECTION", "故事方向", "规划"),
            define("STORY_BIBLE", "故事圣经", "规划"),
            define("OUTLINE", "分层大纲", "规划"),
            define("SNOWFLAKE_PLANNING", "雪花渐进规划", "规划"),
            define("CHARACTER_DESIGN", "统一人物设计", "规划"),
            define("PLANNING_CHECKPOINT", "分块规划", "规划"),
            define("MANUSCRIPT", "正文创作与润色", "写作"),
            define("CHAPTER_REVIEW", "发布后记忆整理", "记忆"),
            define("QUALITY_REVIEW", "文字质量检查", "审阅"),
            define("DRAFT_JUDGE_REVISION", "自动裁决与修订 · C", "审阅"),
            define("FIRST_THREE_CHAPTERS_REVIEW", "前三章连读", "审阅"),
            define("MANUSCRIPT_LOCAL_EDIT", "正文局部编辑", "写作"),
            define("STYLE_ANALYSIS", "样本风格分析", "风格"),
            define("STYLE_RECOMMENDATION", "风格推荐", "风格"),
            define("STYLE_PREVIEW", "风格试写", "风格"),
            define("STYLE_PREVIEW_REVIEW", "试写审阅", "风格"),
            define("STYLE_PREVIEW_REVISION", "试写修订", "风格"),
            define("IMPORT_SOURCE_ANALYSIS", "原文解析", "导入"),
            new Definition("IMPORT_REVERSE_BIBLE_ADAPT", "IMPORT_REVERSE_BIBLE", "导入圣经 · 改编", "导入"),
            new Definition("IMPORT_REVERSE_BIBLE_CONTINUE", "IMPORT_REVERSE_BIBLE", "导入圣经 · 续写", "导入"),
            new Definition("IMPORT_REVERSE_OUTLINE_ADAPT", "IMPORT_REVERSE_OUTLINE", "导入大纲 · 改编", "导入"),
            new Definition("IMPORT_REVERSE_OUTLINE_CONTINUE", "IMPORT_REVERSE_OUTLINE", "导入大纲 · 续写", "导入"));

    public List<Definition> all() { return definitions; }

    public Definition require(String key) {
        return definitions.stream().filter(value -> value.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知提示词模板：" + key));
    }

    /** 用真实的默认系统文本识别导入模式，不从正文关键词猜测改编/续写。 */
    public Optional<Definition> forRequest(String workflow, String originalSystem) {
        var matches = definitions.stream().filter(value -> value.workflow().equals(workflow)).toList();
        if (matches.size() == 1) return Optional.of(matches.getFirst());
        return matches.stream().filter(value -> value.defaultSystemPrompt().equals(originalSystem)).findFirst();
    }

    private static Definition define(String key, String name, String group) {
        return new Definition(key, key, name, group);
    }

    public record Definition(String key, String workflow, String name, String group) {
        public String defaultSystemPrompt() { return AgentPromptDefaults.system(key); }
    }
}
