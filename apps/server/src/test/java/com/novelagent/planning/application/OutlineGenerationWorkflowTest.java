package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineGenerationWorkflowTest {
    @Test
    void runsOutlineGeneratorThroughAgentGraph() {
        OutlineGenerator generator = new OutlineGenerator() {
            @Override public ModelProvider provider() { return ModelProvider.LOCAL_TEMPLATE; }
            @Override public GeneratedOutline generate(UUID projectId, StoryBibleContent bible,
                    OutlineWordBudget budget, OutlineContent previousOutline, String instruction) {
                ChapterPlan chapter = new ChapterPlan(1, "开端", "主角", "目标", "事件", "揭示", "钩子", 2000, 4000);
                OutlineArc arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果", 50000, 70000, List.of(chapter));
                return new GeneratedOutline(provider().name(), new OutlineContent("大纲", "前提", "结构", "节奏",
                        budget.acceptableMinWords(), budget.acceptableMaxWords(), List.of(arc)));
            }
        };
        OutlineGenerationWorkflow workflow = new OutlineGenerationWorkflow(
                new OutlineGeneratorRegistry(List.of(generator)), new ObjectMapper());
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(), "主角", "弧光",
                List.of(), List.of(), "冲突", "代价", "文风", "结局", List.of(), List.of());
        OutlineWordBudget budget = new OutlineWordBudget(120000, 110000, 130000, 2, 40, 3000, 2400, 3600);

        GeneratedOutline result = workflow.generate(
                UUID.randomUUID(), bible, budget, ModelProvider.LOCAL_TEMPLATE, null, null);

        assertThat(result.content().chapterCount()).isEqualTo(1);
        assertThat(result.generatorType()).isEqualTo("LOCAL_TEMPLATE");
    }
}
