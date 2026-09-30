package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingPromptRevisionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void revisionPromptCarriesPreviousManuscriptAndRequestsChangeSummary() {
        CharacterNameService names = mock(CharacterNameService.class);
        CharacterProfileService profiles = mock(CharacterProfileService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(org.mockito.ArgumentMatchers.any())).thenReturn("无");
        WritingPromptFactory factory = new WritingPromptFactory(mapper, names, profiles);
        ManuscriptContent previous = new ManuscriptContent("第一章", "需要保留的原文", "原摘要", List.of());

        WritingPromptFactory.Prompt prompt = factory.manuscript(UUID.randomUUID(), bible(), arc(), chapter(),
                contract(), memory(), previous, "增强对话张力");

        assertThat(prompt.user()).contains("需要保留的原文", "不得借机整体重写", "changeSummary", "实际修改");
    }

    @Test
    void manuscriptPromptRequestsSceneDrivenNaturalProse() {
        CharacterNameService names = mock(CharacterNameService.class);
        CharacterProfileService profiles = mock(CharacterProfileService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(org.mockito.ArgumentMatchers.any())).thenReturn("无");
        WritingPromptFactory factory = new WritingPromptFactory(mapper, names, profiles);

        WritingPromptFactory.Prompt prompt = factory.manuscript(UUID.randomUUID(), bible(), arc(), chapter(),
                contract(), memory(), null, null);

        assertThat(prompt.system()).contains("可信、具体", "不替读者总结");
        assertThat(prompt.user()).contains(
                "以具体场景为基本单位",
                "能让读者自行理解的内容，不再由叙述者解释一遍",
                "不是……而是……",
                "不要让人物轮流完整表达观点",
                "不机械地一两句一段",
                "不得输出检查过程");
    }

    @Test
    void parsesStructuredManuscriptAndChangeSummary() {
        String output = """
                {"content":{"title":"第一章","body":"正文","summary":"摘要","continuityNotes":[]},
                "changeSummary":["增强了人物对话的冲突感。"]}
                """;
        GeneratedManuscript result = new WritingModelOutputParser(mapper).manuscript(output);
        assertThat(result.content().body()).isEqualTo("正文");
        assertThat(result.changeSummary()).containsExactly("增强了人物对话的冲突感。");
    }

    private StoryBibleContent bible() {
        return new StoryBibleContent("故事", "主题", "世界", List.of(), "主角", "弧光", List.of(), List.of(),
                "冲突", "代价", "文风", "结局", List.of(), List.of());
    }
    private ChapterPlan chapter() {
        return new ChapterPlan(1, "第一章", "主角", "目标", "事件", "揭示", "钩子", 2000, 3000);
    }
    private OutlineArc arc() {
        return new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果", 2000, 3000, List.of(chapter()));
    }
    private ChapterContractContent contract() {
        return new ChapterContractContent("第一章", "主角", "目标", "当天", List.of(), List.of(), List.of(),
                List.of(), "退出状态", List.of(), "钩子", 2000, 3000);
    }
    private NovelMemoryContext memory() {
        return new NovelMemoryContext(List.of(), List.of(), new NovelMemoryContext.MemoryUsage(
                "MANUSCRIPT", "LOCAL_TEMPLATE", 32000, 1000, 5000, 2000, 8000, 4000, 8000, 0, false, List.of()));
    }
}
