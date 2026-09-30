package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryBibleGenerationWorkflowTest {
    @Test
    void runsGeneratorThroughAgentGraph() {
        StoryBibleGenerator generator = new StoryBibleGenerator() {
            @Override public ModelProvider provider() { return ModelProvider.LOCAL_TEMPLATE; }

            @Override
            public GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
                    StoryDirectionCandidate direction, StoryBibleContent previousBible, String authorInstruction) {
                return new GeneratedStoryBible(provider().name(), new StoryBibleContent(
                        "一句话", "主题", "世界", List.of("规则"), "主角", "弧光", List.of("配角"),
                        List.of("关系"), "冲突", "代价", "文风", "结局", List.of(), List.of()));
            }
        };
        StoryBibleGenerationWorkflow workflow = new StoryBibleGenerationWorkflow(
                new StoryBibleGeneratorRegistry(List.of(generator)), new ObjectMapper());
        CreativeIntentSnapshot intent = new CreativeIntentSnapshot("前提", List.of("现实"), "读者", "主角",
                "冲突", List.of("克制"), 100000, "结局", List.of(), List.of(), List.of(), 1);
        StoryDirectionCandidate direction = new StoryDirectionCandidate(UUID.randomUUID(), "方向", "前提", "冲突",
                "弧光", "结构", "结局", "读者", List.of(), List.of(), List.of());

        GeneratedStoryBible result = workflow.generate(UUID.randomUUID(), intent, direction,
                ModelProvider.LOCAL_TEMPLATE, null, null);

        assertThat(result.generatorType()).isEqualTo("LOCAL_TEMPLATE");
        assertThat(result.content().logline()).isEqualTo("一句话");
    }
}
