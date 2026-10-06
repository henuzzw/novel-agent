package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.canon.application.EntityCatalogContext;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WritingGenerationWorkflowTest {
    private WritingGenerationGateway gateway;
    private WritingGenerationWorkflow workflow;

    @BeforeEach
    void setUp() {
        gateway = mock(WritingGenerationGateway.class);
        workflow = new WritingGenerationWorkflow(gateway, new ObjectMapper());
    }

    @Test
    void runsContractGenerationThroughGraph() {
        ChapterContractContent generated = contract();
        when(gateway.contract(any(), any(), any(), any(), any(), any(), eq(ModelProvider.LOCAL_TEMPLATE), any()))
                .thenReturn(generated);

        ChapterContractContent result = workflow.generateContract(UUID.randomUUID(), bible(), arc(), chapter(),
                memory(), null, ModelProvider.LOCAL_TEMPLATE, "增强冲突");

        assertThat(result).isEqualTo(generated);
    }

    @Test
    void passesSelectedContractThroughGraph() {
        ChapterContractContent previous = contract();
        when(gateway.contract(any(), any(), any(), any(), any(), eq(previous),
                eq(ModelProvider.LOCAL_TEMPLATE), eq("微调"))).thenReturn(previous);

        ChapterContractContent result = workflow.generateContract(UUID.randomUUID(), bible(), arc(), chapter(),
                memory(), previous, ModelProvider.LOCAL_TEMPLATE, "微调");

        assertThat(result).isEqualTo(previous);
    }

    @Test
    void runsManuscriptGenerationThroughGraph() {
        ManuscriptContent generated = new ManuscriptContent("第一章", "完整正文", "摘要", List.of());
        GeneratedManuscript generatedResult = new GeneratedManuscript(generated, List.of("增强了对话张力。"));
        when(gateway.manuscript(any(), any(), any(), any(), any(), any(), nullable(ManuscriptContent.class),
                eq(ModelProvider.LOCAL_TEMPLATE), nullable(String.class))).thenReturn(generatedResult);

        GeneratedManuscript result = workflow.generateManuscript(UUID.randomUUID(), bible(), arc(), chapter(),
                contract(), memory(), null, ModelProvider.LOCAL_TEMPLATE, null);

        assertThat(result).isEqualTo(generatedResult);
    }

    @Test
    void runsIndependentContractReviewThroughGraph() {
        ChapterContractReviewContent generated = new ChapterContractReviewContent("合同可执行", List.of());
        when(gateway.contractReview(any(), any(), any(), any(), any(), any(),
                eq(ModelProvider.LOCAL_TEMPLATE), eq("检查人物位置"))).thenReturn(generated);

        ChapterContractReviewContent result = workflow.generateContractReview(UUID.randomUUID(), bible(), arc(),
                chapter(), contract(), memory(), ModelProvider.LOCAL_TEMPLATE, "检查人物位置");

        assertThat(result).isEqualTo(generated);
    }

    @Test
    void runsReviewGenerationThroughGraph() {
        ChapterReviewContent generated = new ChapterReviewContent("审稿完成", List.of(), List.of());
        when(gateway.review(any(), any(), any(), any(), any(), any(),
                eq(ModelProvider.LOCAL_TEMPLATE), nullable(String.class)))
                .thenReturn(generated);

        ChapterReviewContent result = workflow.generateReview(UUID.randomUUID(), bible(), contract(),
                new ManuscriptContent("第一章", "完整正文", "摘要", List.of()), memory(),
                EntityCatalogContext.empty(), ModelProvider.LOCAL_TEMPLATE, null);

        assertThat(result).isEqualTo(generated);
    }

    private StoryBibleContent bible() {
        return new StoryBibleContent("一句话故事", "主题", "世界", List.of("规则"), "主角", "成长",
                List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
    }

    @Test
    void runsIndependentPreviewWithExplicitStyleThroughGraph() {
        var profile = WritingStylePresets.all().getFirst();
        var generated = new WritingStylePreviewContent("试写", "她停在门口。");
        when(gateway.stylePreview(any(), any(), any(), any(), eq(profile), eq(ModelProvider.LOCAL_TEMPLATE),
                eq(800), eq("只写开场"))).thenReturn(generated);
        assertThat(workflow.generateStylePreview(UUID.randomUUID(), bible(), arc(), chapter(), profile,
                ModelProvider.LOCAL_TEMPLATE, 800, "只写开场")).isEqualTo(generated);
    }

    @Test
    void rejectsInvalidPreviewBudgetThroughGraph() {
        assertThatThrownBy(() -> workflow.generateStylePreview(UUID.randomUUID(), bible(), arc(), chapter(),
                WritingStylePresets.all().getFirst(), ModelProvider.LOCAL_TEMPLATE, 200, null))
                .hasStackTraceContaining("300 至 1500");
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    private ChapterPlan chapter() {
        return new ChapterPlan(1, "第一章", "顾弦", "找到线索", "发现旧笔记", "笔记属于自己",
                "陌生人敲门", 2400, 3600);
    }

    private OutlineArc arc() {
        return new OutlineArc(1, "第一卷", "建立局面", "记忆与真相", "证据反转", "进入调查",
                40000, 50000, List.of(chapter()));
    }

    private ChapterContractContent contract() {
        return new ChapterContractContent("第一章", "顾弦", "找到线索", "当晚", List.of("宿舍"),
                List.of("发现笔记"), List.of("笔迹属于自己"), List.of("不得揭示真凶"), "决定调查",
                List.of("埋下钥匙"), "有人敲门", 2400, 3600);
    }

    private NovelMemoryContext memory() {
        return new NovelMemoryContext(List.of(), List.of(), new NovelMemoryContext.MemoryUsage(
                "MANUSCRIPT", "LOCAL_TEMPLATE", 32000, 1000, 5000, 2000,
                8000, 4000, 8000, 0, false, List.of()));
    }
}
