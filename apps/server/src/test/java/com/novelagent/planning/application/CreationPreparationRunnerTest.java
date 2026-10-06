package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.CreationPreparationSchema;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreationPreparationRunnerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final CreationPreparationStore store = mock(CreationPreparationStore.class);
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final CreationPreparationSchema schema = new CreationPreparationSchema(mapper, new StoryBibleOutputSchema(mapper), new OutlineOutputSchema(mapper));
    private final CreationPreparationRunner runner = new CreationPreparationRunner(store, models, schema);
    private final UUID project = UUID.randomUUID(), id = UUID.randomUUID();
    private CreationPreparationStore.Task task(int step, String status, long version) {
        return new CreationPreparationStore.Task(id, project, "PREPARE", ModelProvider.DEEPSEEK, "", UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64), mapper.createObjectNode(), 1, 20, status, step, null, null, null, null, null, version, Instant.now());
    }
    @Test void executesExactlyThreeSerialStagesWithIndependentSessionsAndNoTransactionAroundModels() {
        var first = task(0, "RUNNING", 1); var second = task(1, "RUNNING", 3); var third = task(2, "RUNNING", 5);
        when(store.claim(project, id, 0)).thenReturn(first); when(store.claim(project, id, 2)).thenReturn(second); when(store.claim(project, id, 4)).thenReturn(third);
        var ready1 = new CreationPreparationStore.View(task(1, "READY", 2), false, List.of());
        var ready2 = new CreationPreparationStore.View(task(2, "READY", 4), false, List.of());
        var complete = new CreationPreparationStore.View(task(3, "AWAITING_CONFIRMATION", 6), false, List.of());
        when(store.modelInput(any())).thenReturn(mapper.createObjectNode()); when(store.parse("{}")).thenReturn(mapper.createObjectNode());
        when(models.request(eq(project), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), anyString(), any(), anyString(), anyInt(), eq(CodexSessionPolicy.NEW_THREAD))).thenReturn("{}");
        when(store.finish(eq(first), any())).thenReturn(ready1); when(store.finish(eq(second), any())).thenReturn(ready2); when(store.finish(eq(third), any())).thenReturn(complete);
        assertThat(runner.all(project, id, 0)).isSameAs(complete);
        var order = inOrder(store, models);
        order.verify(store).claim(project, id, 0); order.verify(models).request(eq(project), eq("CREATION_PREPARATION_WORLD"), any(), anyString(), anyString(), any(), anyString(), eq(12000), any());
        order.verify(store).finish(eq(first), any()); order.verify(store).claim(project, id, 2);
        order.verify(models).request(eq(project), eq("CREATION_PREPARATION_PLOT"), any(), anyString(), anyString(), any(), anyString(), eq(12000), any());
        order.verify(store).finish(eq(second), any()); order.verify(store).claim(project, id, 4);
        order.verify(models).request(eq(project), eq("CREATION_PREPARATION_REVIEW"), any(), anyString(), anyString(), any(), anyString(), eq(8000), any());
        verify(store, never()).fail(any(), any());
    }
    @Test void failureStopsAllAndNeverAutomaticallyRetriesOrStartsNextStage() {
        var claim = task(0, "RUNNING", 1); when(store.claim(project, id, 0)).thenReturn(claim); when(store.modelInput(any())).thenReturn(mapper.createObjectNode());
        when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any())).thenThrow(new IllegalStateException("timeout"));
        var failed = new CreationPreparationStore.View(task(0, "FAILED", 2), false, List.of()); when(store.get(project, id)).thenReturn(failed);
        assertThat(runner.all(project, id, 0)).isSameAs(failed);
        verify(models, times(1)).request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any());
        verify(store).fail(eq(claim), any()); verify(store, never()).finish(any(), any());
    }
    @Test void rejectsUnavailableClaimBeforeCallingModel() {
        when(store.claim(project, id, 0)).thenThrow(new IllegalArgumentException("来源已变化"));
        assertThatThrownBy(() -> runner.all(project, id, 0)).hasMessageContaining("来源已变化"); verifyNoInteractions(models);
    }
    @Test void schemasRequireAllStructuredFieldsAndSeparateFutureChangesFromWorld() {
        assertThat(schema.world().path("required").toString()).contains("characters", "entities");
        assertThat(schema.plot().path("required").toString()).contains("units", "knowledge", "timeline", "readerExperiencePlans");
        assertThat(schema.review().path("required").toString()).contains("issues", "adjustments", "planLinks");
        assertThat(CreationPreparationPrompt.review()).contains("JSON Pointer", "逐字", "不自动登记", "不声称已逐字检查全部正文");
    }
}
