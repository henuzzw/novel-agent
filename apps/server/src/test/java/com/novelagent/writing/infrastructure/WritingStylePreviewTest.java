package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.application.WritingStylePresets;
import com.novelagent.writing.application.WritingStyleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingStylePreviewTest {
    @Test
    void promptUsesCandidateStyleAndFirstChapterWithoutReadingAppliedStyle() {
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        when(names.render(any(), anyString())).thenAnswer(i -> i.getArgument(1));
        when(profiles.promptContext(any(), any(), any(), any(), any())).thenReturn("人物当前档案");
        var factory = new WritingPromptFactory(new ObjectMapper(), names, profiles, styles, null);
        var bible = new StoryBibleContent("前提", "主题", "世界", List.of(), "主角", "成长", List.of(),
                List.of(), "冲突", "代价", "原始风格", "结局", List.of(), List.of());
        var chapter = new ChapterPlan(1, "旧信", "林雨", "找到信", "门口发现旧信", "署名", "敲门", 2000, 3000);
        var arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果", 10000, 20000, List.of(chapter));
        var candidate = WritingStylePresets.all().getFirst();
        var c = candidate.craft();
        var quote = "只存在于风格样本的引文甲乙";
        candidate = candidate.withCraft(candidate.basePresetId(), candidate.basePresetVersion(),
                new com.novelagent.writing.domain.WritingStyleCraft(c.narratorPosition(), c.paragraphMoves(), c.sentenceMoves(),
                        c.wordChoice(), c.dialogueMoves(), c.rhetoricMoves(), c.sceneVariants(), c.revisionChecks(), c.examples(),
                        List.of(new com.novelagent.writing.domain.WritingStyleCraft.Evidence("paragraphMoves", quote, "只归纳表达规律"))));
        var prompt = factory.stylePreview(UUID.randomUUID(), bible, arc, chapter,
                candidate, 800, "只写开场");
        assertThat(prompt.system()).contains("不是完整章节", "不得覆盖本任务");
        assertThat(prompt.user()).contains("约 800 字", "旧信", "人物当前档案", "本次试写风格", "只写开场");
        assertThat(prompt.user()).contains("涉及误认、认知延迟或视角限制时保留", "不为技法编造动机");
        assertThat(prompt.user()).doesNotContain("已应用项目写作风格时", "章节合同");
        assertThat(prompt.user()).contains(c.paragraphMoves(), "只归纳表达规律").doesNotContain(quote, "\"craft\":");
        assertThat(prompt.user().indexOf(c.paragraphMoves())).isEqualTo(prompt.user().lastIndexOf(c.paragraphMoves()));
        verifyNoInteractions(styles);
    }

    @Test
    void schemaAndParserRejectEmptyOrOversizedOutput() {
        var mapper = new ObjectMapper();
        var schema = new WritingOutputSchemas(mapper).stylePreview();
        assertThat(schema.path("required").size()).isEqualTo(2);
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        var parser = new WritingModelOutputParser(mapper);
        assertThat(parser.stylePreview("{\"title\":\"试写\",\"body\":\"有效正文\"}").body()).isEqualTo("有效正文");
        assertThatThrownBy(() -> parser.stylePreview("{\"title\":\"试写\",\"body\":\" \"}"))
                .isInstanceOf(ModelProviderException.class);
        assertThatThrownBy(() -> parser.stylePreview("{\"title\":\"试写\",\"body\":\"" + "字".repeat(6001) + "\"}"))
                .isInstanceOf(ModelProviderException.class);
    }
}
