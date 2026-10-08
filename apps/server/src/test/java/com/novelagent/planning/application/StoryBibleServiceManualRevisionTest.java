package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionStatus;
import com.novelagent.planning.api.GenerateStoryBibleRequest;
import com.novelagent.planning.infrastructure.StoryDirectionSetRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.planning.infrastructure.StoryDirectionSetRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoryBibleServiceManualRevisionTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID directionId = UUID.randomUUID();
    private final UUID candidateId = UUID.randomUUID();
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final StoryDirectionSetRepository directions = mock(StoryDirectionSetRepository.class);
    private final StoryBibleGenerationWorkflow workflow = mock(StoryBibleGenerationWorkflow.class);
    private final CurrentActorProvider actor = mock(CurrentActorProvider.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final SnowflakePlanningService snowflake = mock(SnowflakePlanningService.class);
    private final StoryBibleService service = new StoryBibleService(projects,
            directions, bibles, workflow, new ProjectAccessService(projects, actor), names, mock(PlanningMaterialSyncService.class), snowflake, new com.fasterxml.jackson.databind.ObjectMapper());
    private final NovelProject project = mock(NovelProject.class);

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        when(actor.currentUserId()).thenReturn(userId);
        when(project.getOwnerId()).thenReturn(userId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(names.render(eq(projectId), any(StoryBibleContent.class), eq(StoryBibleContent.class)))
                .thenAnswer(call -> call.getArgument(1));
        when(bibles.saveAndFlush(any(StoryBibleVersion.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void savesEditedPublishedTextAsNewDraftWithoutPublishingOrCallingModel() {
        StoryBibleVersion published = version(1);
        published.publish();
        StoryBibleContent edited = content("作者改过的一句话故事");
        when(bibles.findByIdAndProjectId(published.getId(), projectId)).thenReturn(Optional.of(published));
        when(bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(published));

        var result = service.createRevision(projectId, published.getId(), published.getRowVersion(), edited);

        assertThat(result.generationNumber()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(StoryBibleStatus.DRAFT);
        assertThat(result.content()).isEqualTo(edited);
        assertThat(result.sourceDirectionSetId()).isEqualTo(directionId);
        assertThat(result.sourceCandidateId()).isEqualTo(candidateId);
        assertThat(result.baseBibleVersionId()).isEqualTo(published.getId());
        assertThat(published.getStatus()).isEqualTo(StoryBibleStatus.PUBLISHED);
        verify(project, never()).publishStoryBible(any());
        verify(workflow, never()).generate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void keepsImportSourceWhenCreatingManualRevision() {
        UUID importId = UUID.randomUUID();
        StoryBibleVersion published = StoryBibleVersion.createFromImport(UUID.randomUUID(), projectId,
                1, "LOCAL_TEMPLATE", null, importId, content("原版"));
        published.publish();
        when(bibles.findByIdAndProjectId(published.getId(), projectId)).thenReturn(Optional.of(published));
        when(bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(published));

        var result = service.createRevision(projectId, published.getId(), published.getRowVersion(),
                content("改版"));

        assertThat(result.sourceImportId()).isEqualTo(importId);
        assertThat(result.baseBibleVersionId()).isEqualTo(published.getId());
    }

    @Test
    void generatesFromSelectedHistoricalVersionAndRecordsItsId() {
        StoryBibleVersion older = version(1);
        StoryBibleVersion latest = version(3);
        StoryDirectionSet source = mock(StoryDirectionSet.class);
        StoryDirectionCandidate candidate = new StoryDirectionCandidate(candidateId, "方向", "前提", "冲突",
                "弧光", "结构", "结局", "读者", List.of(), List.of(), List.of());
        when(directions.findFirstByProjectIdAndStatusOrderByGenerationNumberDesc(projectId, StoryDirectionStatus.SELECTED))
                .thenReturn(Optional.of(source));
        when(source.getDirections()).thenReturn(List.of(candidate));
        when(source.getSelectedCandidateId()).thenReturn(candidateId);
        when(source.getId()).thenReturn(directionId);
        when(bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(latest));
        when(bibles.findByIdAndProjectId(older.getId(), projectId)).thenReturn(Optional.of(older));
        when(workflow.generate(eq(projectId), any(), eq(candidate), any(), eq(older.getContent()), eq("微调")))
                .thenReturn(new GeneratedStoryBible("LOCAL_TEMPLATE", content("基于旧版调整")));

        var result = service.generate(projectId, new GenerateStoryBibleRequest(
                ModelProvider.LOCAL_TEMPLATE, "微调", GenerationMode.REVISE, older.getId()));

        assertThat(result.generationNumber()).isEqualTo(4);
        assertThat(result.baseBibleVersionId()).isEqualTo(older.getId());
        assertThat(result.content().logline()).isEqualTo("基于旧版调整");
    }

    @Test
    void newRealModelBibleReusesAndPersistsFreeTextPlanning() {
        StoryDirectionSet source = selectedDirection();
        var plan = new com.novelagent.planning.domain.SnowflakePlan(UUID.randomUUID(), projectId, "NEW_STORY",
                ModelProvider.DEEPSEEK, "SUCCEEDED", "PLOT", "核心", "人物自由文本", "世界自由文本", "三幕自由文本", null,
                java.time.Instant.now(), java.time.Instant.now());
        when(snowflake.generate(eq(projectId), eq(ModelProvider.DEEPSEEK), any())).thenReturn(plan);
        when(workflow.generate(eq(projectId), any(), any(), eq(ModelProvider.DEEPSEEK), eq(null), any()))
                .thenReturn(new GeneratedStoryBible("DEEPSEEK", content("整合结果")));
        var result = service.generate(projectId, new GenerateStoryBibleRequest(
                ModelProvider.DEEPSEEK, "保留开场", GenerationMode.REGENERATE, null));
        assertThat(result.content().developmentNotes()).isEqualTo(plan.context());
        assertThat(result.authorInstruction()).isEqualTo("保留开场");
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(workflow).generate(eq(projectId), any(), any(), eq(ModelProvider.DEEPSEEK), eq(null), prompt.capture());
        assertThat(prompt.getValue()).contains("保留开场", "人物自由文本", "世界自由文本", "三幕自由文本");
        assertThat(source.getSelectedCandidateId()).isEqualTo(candidateId);
        verify(project, never()).publishStoryBible(any());
    }

    @Test
    void realModelRevisionDoesNotRestartSnowflakeAndKeepsReferenceNotes() {
        selectedDirection();
        StoryBibleVersion base = StoryBibleVersion.create(UUID.randomUUID(), projectId, 1, "DEEPSEEK",
                null, directionId, candidateId, null, content("原版").withDevelopmentNotes("已有自由文本底稿"), List.of());
        when(bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(base));
        when(workflow.generate(eq(projectId), any(), any(), eq(ModelProvider.DEEPSEEK), eq(base.getContent()), any()))
                .thenReturn(new GeneratedStoryBible("DEEPSEEK", content("仅修改结局")));
        var result = service.generate(projectId, new GenerateStoryBibleRequest(
                ModelProvider.DEEPSEEK, "仅修改结局", GenerationMode.REVISE, null));
        org.mockito.Mockito.verifyNoInteractions(snowflake);
        assertThat(result.content().developmentNotes()).isEqualTo("已有自由文本底稿");
    }

    private StoryDirectionSet selectedDirection() {
        StoryDirectionSet source = mock(StoryDirectionSet.class);
        StoryDirectionCandidate candidate = new StoryDirectionCandidate(candidateId, "方向", "前提", "冲突",
                "弧光", "结构", "结局", "读者", List.of(), List.of(), List.of());
        when(directions.findFirstByProjectIdAndStatusOrderByGenerationNumberDesc(projectId, StoryDirectionStatus.SELECTED))
                .thenReturn(Optional.of(source));
        when(source.getDirections()).thenReturn(List.of(candidate));
        when(source.getSelectedCandidateId()).thenReturn(candidateId);
        when(source.getId()).thenReturn(directionId);
        return source;
    }

    @Test
    void rejectsForeignHistoricalVersion() {
        StoryDirectionSet source = mock(StoryDirectionSet.class);
        StoryDirectionCandidate candidate = new StoryDirectionCandidate(candidateId, "方向", "前提", "冲突",
                "弧光", "结构", "结局", "读者", List.of(), List.of(), List.of());
        when(directions.findFirstByProjectIdAndStatusOrderByGenerationNumberDesc(projectId, StoryDirectionStatus.SELECTED))
                .thenReturn(Optional.of(source));
        when(source.getDirections()).thenReturn(List.of(candidate));
        when(source.getSelectedCandidateId()).thenReturn(candidateId);

        assertThatThrownBy(() -> service.generate(projectId, new GenerateStoryBibleRequest(
                ModelProvider.LOCAL_TEMPLATE, "微调", GenerationMode.REVISE, UUID.randomUUID())))
                .isInstanceOf(StoryBibleVersionNotFoundException.class);
        verify(workflow, never()).generate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsDraftAndStalePublishedVersions() {
        StoryBibleVersion draft = version(1);
        when(bibles.findByIdAndProjectId(draft.getId(), projectId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.createRevision(projectId, draft.getId(),
                draft.getRowVersion(), content("新内容")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已发布");

        draft.publish();
        assertThatThrownBy(() -> service.createRevision(projectId, draft.getId(),
                draft.getRowVersion() + 1, content("新内容")))
                .isInstanceOf(ResourceVersionConflictException.class);
    }

    private StoryBibleVersion version(int number) {
        return StoryBibleVersion.create(UUID.randomUUID(), projectId, number, "LOCAL_TEMPLATE", null,
                directionId, candidateId, content("原版"));
    }

    private StoryBibleContent content(String logline) {
        return new StoryBibleContent(logline, "主题", "世界", List.of(), "主角", "弧光", List.of(),
                List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
    }
}
