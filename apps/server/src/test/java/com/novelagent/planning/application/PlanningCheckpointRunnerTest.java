package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.PlanningCheckpoint;
import com.novelagent.planning.domain.PlanningCheckpointResult;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.application.CreativeStrategyGuide;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PlanningCheckpointRunnerTest {
    private final UUID project = UUID.randomUUID(), id = UUID.randomUUID();
    private final PlanningCheckpointService checkpoints = mock(PlanningCheckpointService.class);
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanningCheckpointRunner runner = new PlanningCheckpointRunner(checkpoints, models, mapper,
            new OutlineOutputSchema(mapper));

    private PlanningCheckpoint source(ModelProvider provider) {
        return new PlanningCheckpoint(id, project, "first", 1, 1,
                new PlanningCheckpoint.Source(UUID.randomUUID(), 0,
                        CreativeStrategyPolicy.of(CreativeStrategy.STANDARD), provider, "明确任务", "hash"),
                PlanningCheckpoint.Status.RUNNING, 1, 1, null, null, Instant.now(), Instant.now());
    }

    @Test void templateCannotClaimOrGenerateSemanticPlanning() {
        when(checkpoints.get(project, id)).thenReturn(source(ModelProvider.LOCAL_TEMPLATE));
        assertThatThrownBy(() -> runner.run(project, id, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(models);
    }

    @Test void oneExplicitCallSavesOnlyTheClaimedResult() throws Exception {
        var source = source(ModelProvider.DEEPSEEK);
        when(checkpoints.get(project, id)).thenReturn(source);
        when(checkpoints.claim(project, id, 0)).thenReturn(new PlanningCheckpointService.Claim(source, null));
        var result = new PlanningCheckpointResult(List.of(new OutlineArc(1, "卷", "目标", "阻力", "转折", "结果",
                1000, 2000, List.of(new ChapterPlan(1, "第一章", "人物", "目标", "事件", "揭示", "钩子", 1000, 2000)))));
        when(models.request(eq(project), eq("PLANNING_CHECKPOINT"), eq(ModelProvider.DEEPSEEK), anyString(),
                anyString(), any(), eq("planning_checkpoint_v1"), anyInt(), eq(CodexSessionPolicy.NEW_THREAD)))
                .thenReturn(mapper.writeValueAsString(result));
        when(checkpoints.succeed(project, id, 1, result)).thenReturn(source);
        assertThat(runner.run(project, id, 0)).isEqualTo(source);
        verify(checkpoints).succeed(project, id, 1, result);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(models).request(eq(project), eq("PLANNING_CHECKPOINT"), eq(ModelProvider.DEEPSEEK), anyString(),
                prompt.capture(), any(), eq("planning_checkpoint_v1"), eq(10000), eq(CodexSessionPolicy.NEW_THREAD));
        assertThat(mapper.readTree(prompt.getValue()).path("openingDesignRules").asText())
                .isEqualTo(CreativeStrategyGuide.outlineRules());
    }

    @Test void failedModelRecordsFailureWithoutPublishingOrRetrying() {
        var source = source(ModelProvider.DEEPSEEK);
        when(checkpoints.get(project, id)).thenReturn(source);
        when(checkpoints.claim(project, id, 0)).thenReturn(new PlanningCheckpointService.Claim(source, null));
        when(models.request(eq(project), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenThrow(new IllegalStateException("authentication"));
        assertThatThrownBy(() -> runner.run(project, id, 0)).isInstanceOf(IllegalStateException.class);
        verify(checkpoints).fail(project, id, 1, "IllegalStateException");
    }
}
