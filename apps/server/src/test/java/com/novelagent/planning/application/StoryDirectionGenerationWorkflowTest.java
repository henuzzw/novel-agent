package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryDirectionGenerationWorkflowTest {

    @Test
    void runsGeneratorThroughAgentGraph() {
        StoryDirectionGenerator generator = new StoryDirectionGenerator() {
            @Override
            public ModelProvider provider() {
                return ModelProvider.LOCAL_TEMPLATE;
            }

            @Override
            public GeneratedStoryDirections generate(
                    UUID projectId, CreativeIntentSnapshot intent,
                    List<StoryDirectionCandidate> previousDirections, String authorInstruction) {
                StoryDirectionCandidate candidate = new StoryDirectionCandidate(
                        UUID.randomUUID(), "方向", "前提", "冲突", "弧光", "结构", "结局", "受众",
                        List.of("优势"), List.of("风险"), List.of("特点"));
                return new GeneratedStoryDirections(
                        provider().name(), List.of(candidate, candidate, candidate), List.of());
            }
        };
        StoryDirectionGenerationWorkflow workflow = new StoryDirectionGenerationWorkflow(
                new StoryDirectionGeneratorRegistry(List.of(generator)), new ObjectMapper());
        CreativeIntentSnapshot intent = new CreativeIntentSnapshot(
                "故事前提", List.of("现实"), "成年读者", "主角", "冲突", List.of("克制"),
                100_000, "开放结局", List.of(), List.of(), List.of(), 1L);

        GeneratedStoryDirections result = workflow.generate(
                UUID.randomUUID(), intent, ModelProvider.LOCAL_TEMPLATE, List.of(), null);

        assertThat(result.generatorType()).isEqualTo("LOCAL_TEMPLATE");
        assertThat(result.directions()).hasSize(3);
    }
}
