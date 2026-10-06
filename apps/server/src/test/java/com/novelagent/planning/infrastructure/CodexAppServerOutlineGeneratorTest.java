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
import com.novelagent.project.application.CreativeStrategyGuide;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodexAppServerOutlineGeneratorTest {

    @Test
    void delegatesOutlineGenerationWithFreshThreadPolicy() {
        UUID projectId = UUID.randomUUID();
        StructuredModelGateway models = mock(StructuredModelGateway.class);
        OutlineModelOutputParser parser = mock(OutlineModelOutputParser.class);
        OutlineContent selected = new OutlineContent("选定基准", "前提", "结构", "节奏",
                110_000, 130_000, List.of());
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(),
                "主角", "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000,
                3, 40, 3_000, 2_400, 3_600);
        GeneratedOutline expected = new GeneratedOutline("LOCAL_CODEX", selected, List.of());
        when(models.request(eq(projectId), eq("OUTLINE"), eq(ModelProvider.LOCAL_CODEX),
                anyString(), anyString(), any(), eq("novel_outline"), eq(16_000),
                eq(CodexSessionPolicy.NEW_THREAD))).thenReturn("model-output");
        when(parser.parse(ModelProvider.LOCAL_CODEX, "model-output")).thenReturn(expected);
        CodexAppServerOutlineGenerator generator = new CodexAppServerOutlineGenerator(models,
                new OutlineModelPromptFactory(new ObjectMapper()), parser,
                new OutlineOutputSchema(new ObjectMapper()));

        assertThat(generator.generate(projectId, bible, budget, selected, "微调")).isSameAs(expected);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(models).request(eq(projectId), eq("OUTLINE"), eq(ModelProvider.LOCAL_CODEX),
                anyString(), prompt.capture(), any(), eq("novel_outline"), eq(16_000),
                eq(CodexSessionPolicy.NEW_THREAD));
        assertThat(prompt.getValue()).contains(CreativeStrategyGuide.outlineRules(), "仅切换 policy 不构成重写授权");
    }
}
