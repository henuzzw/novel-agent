package com.novelagent.prompt.application;

import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 23 个工作流的可编辑系统指令；两项导入工作流各分改编、续写模板，避免互相覆盖。 */
@Component
public class AgentPromptCatalog {
    private final List<Definition> definitions = List.of(
            define("STORY_DIRECTION", "故事方向", "规划"),
            define("STORY_BIBLE", "故事圣经", "规划"),
            define("OUTLINE", "分层大纲", "规划"),
            define("CHARACTER_BLUEPRINT_COMPLETION", "人物补全", "规划"),
            define("PLANNING_CHECKPOINT", "分块规划", "规划"),
            define("CREATION_PREPARATION_WORLD", "创作准备 · 人物与世界", "创作准备"),
            define("CREATION_PREPARATION_PLOT", "创作准备 · 剧情协同", "创作准备"),
            define("CREATION_PREPARATION_REVIEW", "创作准备 · 复核", "创作准备"),
            define("CHAPTER_CONTRACT", "章节合同", "写作"),
            define("CHAPTER_CONTRACT_REVIEW", "合同审阅", "写作"),
            define("MANUSCRIPT", "正文创作与润色", "写作"),
            define("CHAPTER_REVIEW", "章节审稿与事实抽取", "审阅"),
            define("QUALITY_REVIEW", "文字质量检查", "审阅"),
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
