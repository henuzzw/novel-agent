package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryDirectionGenerationWorkflowTest {

    @Test void graphKeepsRequestScopeAndPropagatesStoppedGeneration() throws Exception {
        var controls = new com.novelagent.agent.application.GenerationControlRegistry();
        var jdbc = org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var recorder = new com.novelagent.agent.application.AgentRunRecorder(jdbc, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new com.novelagent.agent.application.AgentRunOutputBuffer(), controls);
        var started = new java.util.concurrent.CountDownLatch(1);
        StoryDirectionGenerator generator = new StoryDirectionGenerator() {
            @Override public ModelProvider provider() { return ModelProvider.LOCAL_CODEX; }
            @Override public GeneratedStoryDirections generate(UUID p, CreativeIntentSnapshot intent,
                    List<StoryDirectionCandidate> previous, String instruction) {
                recorder.record(p, "STORY_DIRECTION", provider(), "system", "input", () -> {
                    started.countDown();
                    try { new java.util.concurrent.CountDownLatch(1).await(5, java.util.concurrent.TimeUnit.SECONDS); }
                    catch (InterruptedException error) { throw new IllegalStateException("interrupted", error); }
                    return "late response";
                });
                throw new AssertionError("Cancelled output must never reach this point");
            }
        };
        var workflow = new StoryDirectionGenerationWorkflow(new StoryDirectionGeneratorRegistry(List.of(generator)), new ObjectMapper());
        var intent = new CreativeIntentSnapshot("故事前提", List.of("现实"), "成年读者", "主角", "冲突", List.of("克制"),
                100_000, "开放结局", List.of(), List.of(), List.of(), 1L);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        UUID project = UUID.randomUUID(), requestId = UUID.randomUUID();
        try {
            var future = executor.submit(() -> {
                var request = new org.springframework.mock.web.MockHttpServletRequest();
                request.addHeader(com.novelagent.agent.application.GenerationControlRegistry.REQUEST_HEADER, requestId.toString());
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                        new org.springframework.web.context.request.ServletRequestAttributes(request));
                try { return workflow.generate(project, intent, ModelProvider.LOCAL_CODEX, List.of(), null); }
                finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
            });
            assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            controls.stopRequest(project, requestId);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(3, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.novelagent.agent.application.GenerationStoppedException.class);
        } finally { executor.shutdownNow(); }
    }

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
