package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DeepSeekOutlineGeneratorTest {
    @Test
    void keepsAuthorRequestSeparateFromPolicyWithoutChangingOutputContract() {
        var models = mock(StructuredModelGateway.class);
        var parser = mock(OutlineModelOutputParser.class);
        var mapper = new ObjectMapper();
        var generator = new DeepSeekOutlineGenerator(models, new OutlineModelPromptFactory(mapper),
                parser, new OutlineOutputSchema(mapper));
        UUID projectId = UUID.randomUUID();
        var bible = new StoryBibleContent("故事", "主题", "世界", List.of(), "主角", "弧光",
                List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        var budget = new OutlineWordBudget(120_000, 110_000, 130_000, 3, 40, 3_000, 2_400, 3_600);
        var content = new OutlineContent("大纲", "前提", "结构", "节奏", 110_000, 130_000, List.of());
        var expected = new GeneratedOutline("DEEPSEEK", content);
        when(models.request(eq(projectId), eq("OUTLINE"), eq(ModelProvider.DEEPSEEK), anyString(),
                anyString(), any(), eq("novel_outline"), eq(16_000), eq(CodexSessionPolicy.NEW_THREAD)))
                .thenReturn("model-output");
        when(parser.parse(ModelProvider.DEEPSEEK, "model-output")).thenReturn(expected);

        assertThat(generator.generate(projectId, bible, budget, null, "保留作者指定的成年开场",
                CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING))).isSameAs(expected);

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(models).request(eq(projectId), eq("OUTLINE"), eq(ModelProvider.DEEPSEEK), anyString(),
                prompt.capture(), any(), eq("novel_outline"), eq(16_000), eq(CodexSessionPolicy.NEW_THREAD));
        String authorBlock = prompt.getValue().split("【作者本次要求（本轮修改重点）】", 2)[1]
                .split("【依据优先级】", 2)[0];
        assertThat(authorBlock).contains("保留作者指定的成年开场").doesNotContain("番茄强开篇：");
        assertThat(prompt.getValue()).contains("【项目创作策略（系统辅助规则，不是作者原文）】",
                "项目创作策略：FANQIE_GRIPPING", "以不违反上游边界的作者要求为准");
    }
}
