package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.CreativeBoundaries.AdaptationScope;
import com.novelagent.project.domain.CreativeBoundaries.WorldMode;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.ingest.domain.ImportAnalysis;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CreativeBoundaryServiceTest {
    private final UUID id = UUID.randomUUID(), importId = UUID.randomUUID(), chapterId = UUID.randomUUID();
    private final NovelProject project = NovelProject.create(id, UUID.randomUUID(), "校园故事", EntryMode.MATERIALS);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private CreativeBoundaryService service() {
        when(access.requireOwnedProject(id)).thenReturn(project);
        when(projects.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        return new CreativeBoundaryService(access, projects, entities);
    }
    private ImportAnalysis.WorldAssessment assessment(WorldMode world) {
        return new ImportAnalysis.WorldAssessment(world, "本段世界依据", List.of(new ImportAnalysis.Evidence(chapterId, "原文依据", 0)));
    }

    @Test void defaultsAreConservativeAndPersistedInferenceNeverGrantsRestructuring() {
        var service = service();
        assertThat(service.get(id).effectiveWorld()).isEqualTo(WorldMode.REALISTIC);
        service.assess(id, importId, "hash", List.of(assessment(WorldMode.ALTERNATE)));
        assertThat(service.get(id).effectiveWorld()).isEqualTo(WorldMode.ALTERNATE);
        assertThat(service.get(id).effectiveScope()).isEqualTo(AdaptationScope.PRESERVE_CORE);
        assertThat(service.get(id).assessed()).isTrue();
        assertThat(CreativeBoundaries.from(project).sourceHash()).isEqualTo("hash");
    }
    @Test void explicitChoicesSurviveInferenceAndResetDoesNotGrantOrRewriteAnything() {
        var service = service();
        service.update(id, WorldMode.REALISTIC, AdaptationScope.RESTRUCTURE, 0, false);
        service.assess(id, importId, "hash", List.of(assessment(WorldMode.ALTERNATE)));
        assertThat(service.get(id).effectiveWorld()).isEqualTo(WorldMode.REALISTIC);
        assertThat(service.get(id).effectiveScope()).isEqualTo(AdaptationScope.RESTRUCTURE);
        var reset = service.update(id, WorldMode.AUTO, AdaptationScope.AUTO, 0, true);
        assertThat(reset.assessed()).isFalse();
        assertThat(reset.effectiveScope()).isEqualTo(AdaptationScope.PRESERVE_CORE);
        assertThat(reset.effectiveWorld()).isEqualTo(WorldMode.REALISTIC);
        assertThat(project.getCurrentBibleVersionId()).isNull();
    }
    @Test void sameSourceIsReusedAndChangedSourceIsReassessedWithoutChangingManualChoices() {
        var service = service();
        service.assess(id, importId, "first", List.of(assessment(WorldMode.ALTERNATE)));
        service.assess(id, importId, "first", List.of());
        assertThat(service.get(id).effectiveWorld()).isEqualTo(WorldMode.ALTERNATE);
        service.assess(id, importId, "second", List.of());
        assertThat(service.get(id).effectiveWorld()).isEqualTo(WorldMode.REALISTIC);
        assertThat(CreativeBoundaries.from(project).sourceHash()).isEqualTo("second");
    }
    @Test void laterRealisticSliceDoesNotEraseEvidenceOfFantasyWorld() {
        var service = service();
        service.assess(id, importId, "all", List.of(assessment(WorldMode.ALTERNATE), assessment(WorldMode.REALISTIC)));
        assertThat(service.get(id).effectiveWorld()).isEqualTo(WorldMode.ALTERNATE);
    }
    @Test void staleVersionAndForeignAccessCannotSaveChoices() {
        var service = service();
        assertThatThrownBy(() -> service.update(id, WorldMode.ALTERNATE, AdaptationScope.RESTRUCTURE, 1, false))
                .isInstanceOf(ResourceVersionConflictException.class);
        verify(projects, never()).saveAndFlush(any());
        when(access.requireOwnedProject(id)).thenThrow(new ProjectNotFoundException(id));
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ProjectNotFoundException.class);
    }
    @Test void sourceAssessmentMustUseActualReadSliceAndLiteralEvidence() {
        var slice = new ImportAnalysis.Slice(chapterId, 1, "章节", 0, 4);
        var chapters = List.of(new ImportAnalysis.Chapter(chapterId, 1, "章节", "飞船降落"));
        var batch = new ImportAnalysis.Batch("解析", List.of(),
                new ImportAnalysis.WorldAssessment(WorldMode.ALTERNATE, "科幻", List.of(new ImportAnalysis.Evidence(chapterId, "飞船降落", 99))));
        var normalized = ImportAnalysis.normalizeBatch(batch, slice, chapters);
        assertThat(normalized.worldAssessment().evidence().getFirst().occurrence()).isZero();
        var invalid = new ImportAnalysis.Batch("解析", List.of(), assessment(WorldMode.ALTERNATE));
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(invalid, slice, chapters)).hasMessageContaining("连续逐字引文");
        assertThatThrownBy(() -> new ImportAnalysis.WorldAssessment(WorldMode.ALTERNATE, "无依据", List.of()))
                .hasMessageContaining("原文依据");
    }
}
