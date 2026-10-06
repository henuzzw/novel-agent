package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.project.application.CreativeStrategyService;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.StylePreviewSource;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import org.junit.jupiter.api.Test;

class CreativeStrategyPromptTest {
    @Test
    void selectedStrategyReachesWritingPreviewAndIndependentChecks() throws Exception {
        var mapper = new ObjectMapper();
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        var strategies = mock(CreativeStrategyService.class);
        when(names.render(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(profiles.promptContext(any())).thenReturn("固定人物资料");
        when(styles.promptContext(any())).thenReturn("轻快口语指南");
        String guide = CreativeStrategyGuide.render(CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));
        when(strategies.promptContext(any())).thenReturn(guide);
        var factory = new WritingPromptFactory(mapper, names, profiles, styles, strategies);
        var reference = WritingEvaluationFixtures.capture(WritingEvaluationFixtures.load()).getFirst().input();
        var projectId = WritingEvaluationFixtures.PROJECT_ID;
        var preview = new StylePreviewSource(java.util.UUID.randomUUID(), 0, reference.style(),
                "LOCAL_CODEX", 800, null, new WritingStylePreviewContent("试写", "这是开头样例。"));
        var prompts = java.util.List.of(
                factory.contract(projectId, reference.bible(), reference.arc(), reference.chapter(), reference.memory(), null, null),
                factory.contractReview(projectId, reference.bible(), reference.arc(), reference.chapter(), reference.contract(), reference.memory(), null),
                factory.manuscript(projectId, reference.bible(), reference.arc(), reference.chapter(), reference.contract(), reference.memory(), null),
                factory.qualityReview(projectId, reference.bible(), reference.contract(), reference.manuscript(), reference.memory(), null),
                factory.stylePreview(projectId, reference.bible(), reference.arc(), reference.chapter(), reference.style(), 800, null),
                factory.reviewStylePreview(projectId, reference.bible(), reference.arc(), reference.chapter(), preview));
        for (var prompt : prompts) {
            assertThat(prompt.user()).contains(guide);
            assertThat(prompt.user()).contains("不替代写作风格", "不授权重写旧稿", "不要求片段完成整章");
        }
        assertThat(prompts.get(0).user()).contains(CreativeStrategyGuide.contractRules());
        assertThat(prompts.get(1).user()).contains(CreativeStrategyGuide.reviewRules());
        assertThat(prompts.get(2).user()).contains(CreativeStrategyGuide.manuscriptRules());
        assertThat(prompts.get(3).user()).contains(CreativeStrategyGuide.reviewRules());
        assertThat(prompts.get(4).user()).contains(CreativeStrategyGuide.previewRules());
        assertThat(prompts.get(5).user()).contains(CreativeStrategyGuide.previewReviewRules());
    }

    @Test
    void strategyRulesStayActiveWhenOptionalCraftRulesAreDisabled() throws Exception {
        var names = mock(CharacterNameService.class);
        when(names.render(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var strategies = mock(CreativeStrategyService.class);
        String guide = CreativeStrategyGuide.render(CreativeStrategyPolicy.of(CreativeStrategy.STANDARD));
        when(strategies.promptContext(any())).thenReturn(guide);
        var factory = new WritingPromptFactory(new ObjectMapper(), names, mock(CharacterProfileService.class),
                mock(WritingStyleService.class), strategies, new WritingCraftConfiguration(false));
        var input = WritingEvaluationFixtures.capture(WritingEvaluationFixtures.load()).getFirst().input();
        var prompt = factory.manuscript(WritingEvaluationFixtures.PROJECT_ID, input.bible(), input.arc(),
                input.chapter(), input.contract(), input.memory(), null);
        assertThat(prompt.user()).contains(guide, CreativeStrategyGuide.manuscriptRules(),
                "STANDARD 和后续章不强制首章模式");
        assertThat(prompt.user()).doesNotContain("【正文场景执行规则】");
    }
}
