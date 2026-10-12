package com.novelagent.ingest.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.ingest.api.PrepareImportedStoryRequest;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportedPlanningStore;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.domain.SnowflakePlan;
import com.novelagent.project.api.CreativeIntentRequest;
import com.novelagent.project.api.ProjectResponse;
import com.novelagent.project.application.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImportedStoryBlueprintServiceTest {
    private final UUID projectId = UUID.randomUUID(), importId = UUID.randomUUID(), analysisId = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ImportedPlanningSources sources = mock(ImportedPlanningSources.class);
    private final ImportedPlanningStore store = mock(ImportedPlanningStore.class);
    private final SnowflakePlanningService snowflake = mock(SnowflakePlanningService.class);
    private final ProjectService projects = mock(ProjectService.class);
    private final ProjectResponse project = mock(ProjectResponse.class);
    private final SnowflakePlan plan = mock(SnowflakePlan.class);
    private final PrepareImportedStoryRequest request = new PrepareImportedStoryRequest(ModelProvider.DEEPSEEK,
            ImportPlanningMode.ADAPT_SOURCE, "保留关系主线", analysisId, 2L, 50000, false);
    private final ImportedStoryBlueprintService service = new ImportedStoryBlueprintService(sources, store, snowflake, projects, mapper);

    @BeforeEach void setup() {
        when(sources.load(projectId, importId, analysisId, 2L, request.mode())).thenReturn(
                new ImportedPlanningSources.Snapshot(mapper.createObjectNode().put("summary", "已确认"),
                        new WorkImportService.PlanningSource("原文", 1, 2, false)));
        when(projects.get(projectId)).thenReturn(project);
        when(snowflake.generate(eq(projectId), eq(request.provider()), any())).thenReturn(plan);
        when(plan.core()).thenReturn("故事核心");
        when(plan.steps()).thenReturn(Map.of("CHARACTER_ARCS", "人物弧线"));
    }

    @Test void generationKeepsFrozenInputsChecksConfirmationAndCompletesWithoutPublishing() {
        assertThat(service.generate(projectId, importId, request)).isSameAs(plan);
        var order = inOrder(sources, projects, store, snowflake);
        order.verify(sources).load(projectId, importId, analysisId, 2L, request.mode());
        order.verify(projects).get(projectId);
        order.verify(store).claim(projectId, importId, request.mode(), false);
        var input = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(snowflake).generate(eq(projectId), eq(request.provider()), input.capture());
        assertThat(input.getValue().path("sourceImportId").asText()).isEqualTo(importId.toString());
        assertThat(input.getValue().path("sourceText").asText()).isEqualTo("原文");
        assertThat(input.getValue().path("authorInstruction").asText()).isEqualTo(request.instruction());
        assertThat(input.getValue().path("targetWords").asInt()).isEqualTo(50000);
        order.verify(sources).requireConfirmed(projectId, importId, analysisId, 2L, request.mode());
        var intent = ArgumentCaptor.forClass(CreativeIntentRequest.class);
        order.verify(projects).updateCreativeIntent(eq(projectId), eq(0L), intent.capture());
        assertThat(intent.getValue().premise()).isEqualTo("故事核心");
        assertThat(intent.getValue().protagonistBrief()).isEqualTo("人物弧线");
        order.verify(sources).requireConfirmed(projectId, importId, analysisId, 2L, request.mode());
        order.verify(store).ready(projectId, importId);
        verifyNoMoreInteractions(snowflake);
    }

    @Test void existingIntentKeepsAuthorSettingsAndOnlyUpdatesChangedTargetWords() {
        var intent = new ProjectResponse.CreativeIntentResponse("作者核心", List.of("校园"), "学生", "角色",
                "作者矛盾", List.of("轻快"), 40000, "结局", List.of("保留"), List.of("禁止"), List.of("风格"), 3L);
        when(project.creativeIntent()).thenReturn(intent);
        service.generate(projectId, importId, request);
        var edit = ArgumentCaptor.forClass(CreativeIntentRequest.class);
        verify(projects).updateCreativeIntent(eq(projectId), eq(3L), edit.capture());
        assertThat(edit.getValue()).isEqualTo(new CreativeIntentRequest(intent.premise(), intent.genres(),
                intent.targetAudience(), intent.protagonistBrief(), intent.centralConflict(), intent.tones(), 50000,
                intent.endingPreference(), intent.mustHave(), intent.avoid(), intent.stylePreferences()));
    }

    @Test void sameTargetDoesNotRewriteExistingAuthorIntent() {
        var intent = mock(ProjectResponse.CreativeIntentResponse.class);
        when(intent.targetWords()).thenReturn(50000);
        when(project.creativeIntent()).thenReturn(intent);
        service.generate(projectId, importId, request);
        verify(projects, never()).updateCreativeIntent(any(), anyLong(), any());
        verify(store).ready(projectId, importId);
    }

    @Test void lostConfirmationAfterGenerationCannotPublishReadyOrUpdateIntent() {
        when(sources.requireConfirmed(projectId, importId, analysisId, 2L, request.mode()))
                .thenThrow(new IllegalArgumentException("确认已失效"));
        assertThatThrownBy(() -> service.generate(projectId, importId, request)).hasMessage("确认已失效");
        verify(store).failed(eq(projectId), eq(importId), any(IllegalArgumentException.class));
        verify(store, never()).ready(any(), any());
        verify(projects, never()).updateCreativeIntent(any(), anyLong(), any());
    }

    @Test void duplicateClaimCannotStartModelsOrChangeFailureStatus() {
        doThrow(new IllegalArgumentException("重复启动")).when(store).claim(projectId, importId, request.mode(), false);
        assertThatThrownBy(() -> service.generate(projectId, importId, request)).hasMessage("重复启动");
        verifyNoInteractions(snowflake);
        verify(sources, never()).requireConfirmed(any(), any(), any(), anyLong(), any());
        verify(store, never()).failed(any(), any(), any());
    }

    @Test void cancellationKeepsOriginalCauseEvenWhenSavingStatusFails() {
        var stopped = new GenerationStoppedException();
        var saveFailure = new IllegalStateException("状态写入失败");
        when(snowflake.generate(eq(projectId), any(), any())).thenThrow(stopped);
        doThrow(saveFailure).when(store).failed(projectId, importId, stopped);
        assertThatThrownBy(() -> service.generate(projectId, importId, request))
                .isSameAs(stopped).hasSuppressedException(saveFailure);
        verify(store, never()).ready(any(), any());
        verify(projects, never()).updateCreativeIntent(any(), anyLong(), any());
    }

    @Test void changedResumeSourceIsRejectedBeforeClaimingOrInvokingModels() {
        UUID planId = UUID.randomUUID();
        when(snowflake.get(projectId, planId)).thenReturn(plan);
        when(plan.id()).thenReturn(planId);
        when(plan.provider()).thenReturn(request.provider());
        when(plan.status()).thenReturn("CANCELLED");
        when(snowflake.latest(projectId)).thenReturn(Optional.of(plan));
        var input = mapper.createObjectNode().put("sourceImportId", importId.toString()).put("mode", request.mode().name())
                .put("sourceAnalysisId", analysisId.toString()).put("sourceAnalysisVersion", 2L).put("targetWords", 50000);
        when(snowflake.input(projectId, planId)).thenReturn(input);
        doThrow(new IllegalArgumentException("来源已改变")).when(sources)
                .requireUnchanged(eq(projectId), eq(importId), eq(input), anyString());
        assertThatThrownBy(() -> service.resume(projectId, importId, planId)).hasMessage("来源已改变");
        verifyNoInteractions(store, projects);
        verify(snowflake, never()).resume(any(), any());
        verify(snowflake, never()).generate(any(), any(), any());
    }
}
