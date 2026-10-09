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
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.application.WritingGenerationWorkflow;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.WritingStyleRecommendationContent;
import com.novelagent.writing.domain.WritingStyleRecommendationContent.Evidence;
import com.novelagent.writing.domain.WritingStyleRecommendationContent.Recommendation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingStyleRecommendationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryBibleContent bible = new StoryBibleContent("旧信的故事", "记忆与信任", "城市", List.of(),
            "主角", "成长", List.of(), List.of(), "误会", "代价", "冷静观察", "和解", List.of(), List.of());

    private WritingStyleRecommendationContent recommendation(String presetName, String field, String quote) {
        return new WritingStyleRecommendationContent("根据主题推荐", List.of(new Recommendation(presetName,
                "通过生活细节表现关系", "不稀释核心冲突", List.of(new Evidence(field, quote)))));
    }

    @Test
    void schemaIsStrictAndIncludesAllElevenCanonicalPresets() {
        var schema = new WritingOutputSchemas(mapper).styleRecommendation(com.novelagent.writing.application.WritingStylePresets.all());
        var item = schema.path("properties").path("recommendations").path("items");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(item.path("additionalProperties").asBoolean()).isFalse();
        assertThat(item.path("properties").path("presetName").path("enum").size()).isEqualTo(11);
        assertThat(item.path("required").size()).isEqualTo(4);
    }

    @Test
    void promptUsesBibleAndCanonicalPresetsWithoutReadingAppliedStyleOrMemory() {
        var names = mock(CharacterNameService.class);
        when(names.render(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        var factory = new WritingPromptFactory(mapper, names, profiles, styles, null);
        when(styles.presets(any())).thenReturn(com.novelagent.writing.application.WritingStylePresets.all());
        var prompt = factory.styleRecommendation(UUID.randomUUID(), bible, "不加方言");
        assertThat(prompt.user()).contains(bible.logline(), "不加方言");
        verifyNoInteractions(profiles);
        org.mockito.Mockito.verify(styles).presets(any());
        org.mockito.Mockito.verifyNoMoreInteractions(styles);
    }

    @Test
    void parserAcceptsOnlyKnownPresetsWithEvidenceInTheSpecifiedField() throws Exception {
        var parser = new WritingModelOutputParser(mapper);
        var content = recommendation("现实细腻", "theme", "记忆");
        var presets = com.novelagent.writing.application.WritingStylePresets.all();
        assertThat(parser.styleRecommendation(mapper.writeValueAsString(content), bible, presets)).isEqualTo(content);
        for (var invalid : List.of(recommendation("未知文风", "theme", "记忆"),
                recommendation("现实细腻", "theme", "不存在的主题"),
                recommendation("现实细腻", "worldSetting", "记忆"),
                recommendation("现实细腻", "unknownField", "城市"))) {
            assertThatThrownBy(() -> parser.styleRecommendation(mapper.writeValueAsString(invalid), bible, presets))
                    .isInstanceOf(ModelProviderException.class);
        }
        assertThatThrownBy(() -> parser.styleRecommendation("{\"summary\":\"无\",\"recommendations\":[]}", bible, presets))
                .isInstanceOf(ModelProviderException.class);
        assertThatThrownBy(() -> parser.styleRecommendation("{\"summary\":\"无\"}", bible, presets))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test
    void contentRejectsDuplicatesExcessOrMissingEvidenceAndIsImmutable() {
        var content = recommendation("现实细腻", "theme", "记忆");
        var item = content.recommendations().getFirst();
        assertThatThrownBy(() -> new WritingStyleRecommendationContent("建议", List.of(item, item)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritingStyleRecommendationContent("建议", List.of(item, item, item, item)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recommendation("现实细腻", "原因", "取舍", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> content.recommendations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void graphValidatesGroundedRecommendationsAndRejectsUngroundedResults() {
        var gateway = mock(WritingGenerationGateway.class);
        var workflow = new WritingGenerationWorkflow(gateway, mapper);
        var content = recommendation("现实细腻", "theme", "记忆");
        when(gateway.recommendStyle(any(), any(), any(), any())).thenReturn(content);
        assertThat(workflow.recommendStyle(UUID.randomUUID(), bible, ModelProvider.DEEPSEEK, "偏好")).isEqualTo(content);
        when(gateway.recommendStyle(any(), any(), any(), any())).thenReturn(recommendation("现实细腻", "theme", "伪造"));
        assertThatThrownBy(() -> workflow.recommendStyle(UUID.randomUUID(), bible, ModelProvider.DEEPSEEK, "偏好"))
                .hasStackTraceContaining("证据不在");
        when(gateway.recommendStyle(any(), any(), any(), any())).thenReturn(new WritingStyleRecommendationContent("未推荐", List.of()));
        assertThatThrownBy(() -> workflow.recommendStyle(UUID.randomUUID(), bible, ModelProvider.DEEPSEEK, "偏好"))
                .hasStackTraceContaining("未返回风格推荐");
    }

    @Test
    void localTemplateDoesNotPretendToRecommendStyles() {
        var models = mock(WritingModelRouter.class);
        var gateway = new WritingGenerationGateway(null, null, null, null, models, null, null);
        var workflow = new WritingGenerationWorkflow(gateway, mapper);
        var content = workflow.recommendStyle(UUID.randomUUID(), bible, ModelProvider.LOCAL_TEMPLATE, null);
        assertThat(content.recommendations()).isEmpty();
        assertThat(content.summary()).contains("不判断", "Codex 或 DeepSeek");
        verifyNoInteractions(models);
    }
}
