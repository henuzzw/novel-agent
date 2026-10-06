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
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.application.WritingStyleGuide;
import com.novelagent.writing.application.WritingStylePresets;
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
        WritingPromptFactory factory = new WritingPromptFactory(mapper, names, profiles, mock(WritingStyleService.class), null);
        ManuscriptContent previous = new ManuscriptContent("第一章", "需要保留的原文", "原摘要", List.of());

        WritingPromptFactory.Prompt prompt = factory.manuscript(UUID.randomUUID(), bible(), arc(), chapter(),
                contract(), memory(), previous, "增强对话张力");

        assertThat(prompt.user()).contains("选定的基准正文版本（完整 JSON）", "需要保留的原文",
                "当前章节合同", "不得借机整体重写", "changeSummary", "实际修改");
        assertThat(prompt.system()).contains("降低模板化的 AI 写作感", "让读者愿意跟随人物继续读下去");
        assertThat(prompt.user()).contains("修订旧稿时只在确有必要的地方改善节奏和表达");
        assertThat(prompt.user()).contains("只处理获授权的问题层次", "仅润色语句时保留事件及其先后",
                "不擅自删并场景或改变情节");
    }

    @Test
    void contractRevisionPromptCarriesCompleteSelectedContract() {
        CharacterNameService names = mock(CharacterNameService.class);
        CharacterProfileService profiles = mock(CharacterProfileService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(org.mockito.ArgumentMatchers.any())).thenReturn("无");
        WritingPromptFactory factory = new WritingPromptFactory(mapper, names, profiles, mock(WritingStyleService.class), null);

        WritingPromptFactory.Prompt prompt = factory.contract(UUID.randomUUID(), bible(), arc(), chapter(),
                memory(), contract(), "只调整必写节拍");

        assertThat(prompt.user()).contains("选定的基准章节合同（完整 JSON）", "退出状态", "钩子",
                "未受影响的目标", "只调整必写节拍");
        assertThat(prompt.system()).contains("当前故事圣经", "章节计划优先于历史合同");
    }

    @Test
    void manuscriptPromptRequestsSceneDrivenNaturalProse() {
        CharacterNameService names = mock(CharacterNameService.class);
        CharacterProfileService profiles = mock(CharacterProfileService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(org.mockito.ArgumentMatchers.any())).thenReturn("无");
        WritingPromptFactory factory = new WritingPromptFactory(mapper, names, profiles, mock(WritingStyleService.class), null);

        WritingPromptFactory.Prompt prompt = factory.manuscript(UUID.randomUUID(), bible(), arc(), chapter(),
                contract(), memory(), null, null);

        assertThat(prompt.system()).contains("可信、具体", "不替读者总结", "具体冲突、信息变化和人物选择");
        assertThat(prompt.system()).contains("前两章上下文用于衔接", "有效正史事实优先");
        assertThat(prompt.user()).contains(
                "以具体场景为基本单位",
                "能让读者自行理解的内容，不再由叙述者解释一遍",
                "不是……而是……",
                "不要让人物轮流完整表达观点",
                "不机械地一两句一段",
                "不得输出检查过程",
                "每个主要场景写清人物眼前想要什么、遇到什么阻力",
                "不靠空喊悬念或频繁反转",
                "不强迫每段和每章都制造钩子");
        assertThat(prompt.user()).contains("用准确动词", "涉及误认、认知延迟或视角限制时保留",
                "不规定感官、比喻或句长配额", "避免双方复述彼此早已知道的背景", "不为技法编造动机");
        assertThat(prompt.user()).doesNotContain("只处理获授权的问题层次", "scripts/draft_diagnostics.py");
    }

    @Test
    void appliedStyleReachesBothWritingAndQualityPrompts() {
        UUID project = UUID.randomUUID();
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> call.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(project)).thenReturn("人物声线");
        org.mockito.Mockito.when(styles.promptContext(project)).thenReturn("悬疑克制：动作呈现情绪");
        var factory = new WritingPromptFactory(mapper, names, profiles, styles, null);
        var manuscript = new ManuscriptContent("标题", "正文", "摘要", List.of());
        assertThat(factory.manuscript(project, bible(), arc(), chapter(), contract(), memory(), null, null).user())
                .contains("悬疑克制：动作呈现情绪", "项目写作风格");
        assertThat(factory.qualityReview(project, bible(), contract(), manuscript, memory(), null).user())
                .contains("悬疑克制：动作呈现情绪", "每条 evidence", "视角与事实优先",
                        "分别归入现有四类，不新增评分维度", "平静对白本身不是错误", "不按比喻密度");
    }

    @Test
    void sameExecutionGuideReachesGenerationPreviewReviewAndAuthorizedRevision() {
        UUID project = UUID.randomUUID();
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> call.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(project)).thenReturn("人物声线");
        var candidate = WritingStylePresets.all().get(1);
        String guide = WritingStyleGuide.render(candidate);
        org.mockito.Mockito.when(styles.promptContext(project)).thenReturn(guide);
        var factory = new WritingPromptFactory(mapper, names, profiles, styles, null);
        var manuscript = new ManuscriptContent("标题", "保留这段正文", "摘要", List.of());

        var draft = factory.manuscript(project, bible(), arc(), chapter(), contract(), memory(), null, null);
        var revision = factory.manuscript(project, bible(), arc(), chapter(), contract(), memory(), manuscript, "只调整句式");
        var preview = factory.stylePreview(project, bible(), arc(), chapter(), candidate, 800, null);
        var review = factory.qualityReview(project, bible(), contract(), manuscript, memory(), null);

        for (var prompt : List.of(draft, revision, preview, review)) {
            assertThat(prompt.user()).contains(guide);
        }
        assertThat(draft.system()).contains("从第一稿就落实选定风格", "示例不能成为本书事实");
        assertThat(draft.user()).contains("不是统一文风", "不一律要求少比喻", "必要的直接心理表达可以保留");
        assertThat(revision.user()).contains("只处理获授权的问题层次", "保留这段正文", "不得借机整体重写");
        assertThat(review.user()).contains("description 指明偏离的档案维度", "suggestion 写出怎样落实该特征",
                "不要求每项都报问题", "每条 evidence 必须是 body 中逐字存在的连续原文");
        assertThat(preview.user()).doesNotContain("章节合同", "已应用项目写作风格时");
    }

    @Test
    void sampleAnalysisRequestsActionableRulesAndDoesNotInventUnsupportedTraits() {
        var factory = new WritingPromptFactory(mapper, mock(CharacterNameService.class),
                mock(CharacterProfileService.class), mock(WritingStyleService.class), null);
        var prompt = factory.styleAnalysis("仅用于测试的样本文本");
        assertThat(prompt.user()).contains("可执行规律", "何时使用", "哪些场景应减弱或保留例外",
                "只总结样本支持的特征", "无法判断的维度", "不复制样本原句", "不超过 600 字");
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

    @Test
    void previewEditorUsesCandidateAndChecksDetailRolesWithValidCounterExamples() {
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        org.mockito.Mockito.when(names.render(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> call.getArgument(1));
        org.mockito.Mockito.when(profiles.promptContext(org.mockito.ArgumentMatchers.any())).thenReturn("人物资料");
        var factory = new WritingPromptFactory(mapper, names, profiles, styles, null);
        var source = new com.novelagent.writing.domain.StylePreviewSource(UUID.randomUUID(), 2, WritingStylePresets.all().get(1),
                "DEEPSEEK", 800, null, new com.novelagent.writing.domain.WritingStylePreviewContent("选座", "教室后门开着。"));
        var review = factory.reviewStylePreview(UUID.randomUUID(), bible(), arc(), chapter(), source);
        assertThat(review.system()).contains("独立", "不创作", "不能覆盖检查职责");
        assertThat(review.user()).contains("不按整章字数", "正文已明确工作人员交付登记", "我帮她数学、她帮我英语",
                "不是逻辑错误", "缺失的信息", "正在推早已稳固的桌子", "不按‘名单’关键词判错", source.content().body());
        org.mockito.Mockito.verifyNoInteractions(styles);
        var revision = factory.reviseStylePreview(UUID.randomUUID(), bible(), arc(), chapter(), source, "只删除无用布景");
        assertThat(revision.user()).contains("基准试写（完整 JSON）", "只删除无用布景", "不得为修补旧句新增道具来源", "谁帮助谁");
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
