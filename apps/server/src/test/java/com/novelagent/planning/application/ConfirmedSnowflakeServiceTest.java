package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.ImportedPlanningSources;
import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.planning.domain.*;
import com.novelagent.planning.infrastructure.*;
import com.novelagent.project.application.*;
import com.novelagent.project.domain.*;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ConfirmedSnowflakeServiceTest {
    private final UUID projectId = UUID.randomUUID(), planId = UUID.randomUUID();
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final SnowflakePlanStore plans = mock(SnowflakePlanStore.class);
    private final StoryBibleVersionRepository snapshots = mock(StoryBibleVersionRepository.class);
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final PlanningMaterialSyncService sync = mock(PlanningMaterialSyncService.class);
    private final NovelProject project = mock(NovelProject.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ImportAnalysisStore analyses = mock(ImportAnalysisStore.class);
    private final WorkImportService imports = mock(WorkImportService.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final ConfirmedSnowflakeService service = new ConfirmedSnowflakeService(access, plans, snapshots,
            projects, sync, names, mapper, mock(JdbcTemplate.class), new ImportedPlanningSources(analyses, imports));
    private ObjectNode input;
    private SnowflakePlan plan;

    @BeforeEach void setup() {
        when(access.requireOwnedProject(projectId)).thenReturn(project);
        input = mapper.createObjectNode().put("expandScenes", false)
                .put("creativeBoundarySnapshot", CreativeBoundaryGuide.render(CreativeBoundaries.from(project)))
                .put("creativeStrategySnapshot", CreativeStrategyGuide.render(CreativeStrategyPolicy.from(project)));
        when(plans.input(projectId, planId)).thenReturn(input);
        Map<String, String> steps = new LinkedHashMap<>();
        for (var step : SnowflakeStep.values()) if (step != SnowflakeStep.SCENE_EXPANSION) steps.put(step.name(), "已完成" + step.name());
        plan = new SnowflakePlan(planId, projectId, "NEW_STORY", ModelProvider.DEEPSEEK, "SUCCEEDED", "SCENE_LIST",
                "核心", "人物", "世界", "情节", null, Instant.now(), Instant.now(), steps);
        when(plans.get(projectId, planId)).thenReturn(plan);
        when(plans.latest(projectId)).thenReturn(Optional.of(plan));
        var content = new StoryBibleContent("共同参赛", "成长", "校园", List.of("现实学校"), "陆舟", "合作",
                List.of(), List.of(), "协作阻力", "信任", "清楚", "共同选择", List.of(), List.of());
        when(plans.materials(projectId, planId)).thenReturn(mapper.valueToTree(content));
        when(snapshots.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void confirmationPinsExactPlanPublishesProjectionAndSynchronizesMaterialsWithoutGeneration() {
        service.confirm(projectId, planId);
        var saved = ArgumentCaptor.forClass(StoryBibleVersion.class);
        verify(snapshots).saveAndFlush(saved.capture());
        var value = saved.getValue();
        assertThat(value.getSourceSnowflakeId()).isEqualTo(planId);
        assertThat(value.getSourceCandidateId()).isNull();
        assertThat(value.getSourceDirectionSetId()).isNull();
        assertThat(value.getGeneratorType()).isEqualTo("SNOWFLAKE_CONFIRMED");
        assertThat(value.getStatus()).isEqualTo(StoryBibleStatus.PUBLISHED);
        assertThat(value.getContent().developmentNotes()).isEqualTo(plan.context());
        verify(project).publishStoryBible(value.getId());
        verify(sync).syncBible(value);
        verifyNoInteractions(analyses, imports);
    }

    @Test void confirmationReusesExactSnapshot() {
        var existing = mock(StoryBibleVersion.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(snapshots.findByProjectIdAndSourceSnowflakeIdAndSourceDirectionSetIdIsNull(
                projectId, planId)).thenReturn(Optional.of(existing));
        service.confirm(projectId, planId);
        verify(snapshots, never()).saveAndFlush(any());
        verify(project).publishStoryBible(existing.getId());
        verify(sync).syncBible(existing);
    }

    @Test void stalePlanCannotBecomeCurrentMaterials() {
        when(plans.latest(projectId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.confirm(projectId, planId)).hasMessageContaining("最新");
        verifyNoInteractions(snapshots, projects, sync);
    }

    @Test void missingStageAndChangedBoundaryAreRejectedBeforeWriting() {
        input.put("expandScenes", true);
        assertThatThrownBy(() -> service.confirm(projectId, planId)).hasMessageContaining("步骤未完整");
        input.put("expandScenes", false).put("creativeBoundarySnapshot", "已变更");
        assertThatThrownBy(() -> service.confirm(projectId, planId)).hasMessageContaining("边界或策略已改变");
        verifyNoInteractions(snapshots, projects, sync);
    }

    @Test void stoppedRunCannotBeConfirmed() {
        var stopped = mock(SnowflakePlan.class);
        when(stopped.status()).thenReturn("CANCELLED");
        when(plans.get(projectId, planId)).thenReturn(stopped);
        assertThatThrownBy(() -> service.confirm(projectId, planId)).hasMessageContaining("完整成功");
        verifyNoInteractions(snapshots, projects, sync);
    }

    @Test void repeatedConfirmationDoesNotOverwriteAuthorEditedMaterials() {
        var existing = mock(StoryBibleVersion.class);
        UUID snapshotId = UUID.randomUUID();
        when(existing.getId()).thenReturn(snapshotId);
        when(project.getCurrentBibleVersionId()).thenReturn(snapshotId);
        when(snapshots.findByProjectIdAndSourceSnowflakeIdAndSourceDirectionSetIdIsNull(projectId, planId))
                .thenReturn(Optional.of(existing));
        service.confirm(projectId, planId);
        verify(snapshots, never()).saveAndFlush(any());
        verifyNoInteractions(projects, sync);
    }

    @Test void ownershipIsRequiredBeforeReadingOrPublishingThePlan() {
        when(access.requireOwnedProject(projectId)).thenThrow(new IllegalArgumentException("无权访问"));
        assertThatThrownBy(() -> service.confirm(projectId, planId)).hasMessageContaining("无权访问");
        verifyNoInteractions(plans, snapshots, projects, sync);
    }

    @Test void versionReadsTheExactProjectSnapshotAndRendersNamesWithoutWriting() {
        UUID versionId = UUID.randomUUID();
        var content = mapper.convertValue(plans.materials(projectId, planId), StoryBibleContent.class);
        var snapshot = StoryBibleVersion.create(versionId, projectId, 1, "SNOWFLAKE_CONFIRMED", "", null, null, content);
        when(snapshots.findByIdAndProjectId(versionId, projectId)).thenReturn(Optional.of(snapshot));
        when(names.render(projectId, content, StoryBibleContent.class)).thenReturn(content);

        var result = service.version(projectId, versionId);

        assertThat(result.content()).isEqualTo(content);
        verify(access).requireOwnedProject(projectId);
        verify(snapshots).findByIdAndProjectId(versionId, projectId);
        verify(names).render(projectId, content, StoryBibleContent.class);
        verifyNoMoreInteractions(snapshots);
        verifyNoInteractions(projects, sync);
    }

    @Test void missingSnapshotAndUnauthorizedReadAreRejectedWithoutWriting() {
        UUID versionId = UUID.randomUUID();
        assertThatThrownBy(() -> service.version(projectId, versionId))
                .isInstanceOf(StoryBibleVersionNotFoundException.class);
        clearInvocations(snapshots);
        when(access.requireOwnedProject(projectId)).thenThrow(new IllegalArgumentException("无权访问"));
        assertThatThrownBy(() -> service.version(projectId, versionId)).hasMessageContaining("无权访问");
        verifyNoInteractions(snapshots, names, projects, sync);
    }
}
