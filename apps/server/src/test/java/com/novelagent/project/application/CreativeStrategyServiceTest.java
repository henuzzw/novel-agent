package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CreativeStrategyServiceTest {
    private final UUID owner = UUID.randomUUID();
    private final NovelProject project = NovelProject.create(UUID.randomUUID(), owner, "故事", EntryMode.MATERIALS);
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final CreativeStrategyService service = new CreativeStrategyService(
            new ProjectAccessService(projects, new CurrentActorProvider(owner)), projects, entities);

    @Test
    void oldProjectDefaultsToStandardWithoutWriting() {
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        assertThat(service.get(project.getId())).isEqualTo(new CreativeStrategyService.State(CreativeStrategy.STANDARD, 1, 0));
        assertThat(project.getSetting(CreativeStrategyPolicy.SETTING_KEY)).isNull();
        verify(projects, never()).saveAndFlush(project);
    }

    @Test
    void strategyPreservesStyleAndReturnsPersistedRowVersion() {
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        project.setSetting("writingStyle", Map.of("name", "轻快口语"));
        when(projects.saveAndFlush(project)).thenAnswer(call -> {
            ReflectionTestUtils.setField(project, "rowVersion", 1L);
            return project;
        });
        assertThat(service.update(project.getId(), CreativeStrategy.FANQIE_GRIPPING, 0))
                .isEqualTo(new CreativeStrategyService.State(CreativeStrategy.FANQIE_GRIPPING, 1, 1));
        assertThat(project.getSetting("writingStyle")).isEqualTo(Map.of("name", "轻快口语"));
        assertThat(service.promptContext(project.getId())).contains("番茄强开篇", "第一章", "第二章", "第三章",
                "不临时开挂", "开头试写", "不要求片段完成整章");
        verify(entities).refresh(project, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void staleAndForeignUpdatesCannotWrite() {
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        ReflectionTestUtils.setField(project, "rowVersion", 3L);
        assertThatThrownBy(() -> service.update(project.getId(), CreativeStrategy.FANQIE_GRIPPING, 0))
                .isInstanceOf(ResourceVersionConflictException.class);
        UUID foreignId = UUID.randomUUID();
        when(projects.findById(foreignId)).thenReturn(Optional.of(NovelProject.create(foreignId, UUID.randomUUID(), "别人的故事", EntryMode.MATERIALS)));
        assertThatThrownBy(() -> service.get(foreignId)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.update(foreignId, CreativeStrategy.STANDARD, 0)).isInstanceOf(ProjectNotFoundException.class);
        verify(projects, never()).saveAndFlush(project);
    }

    @Test
    void corruptSavedPolicyIsRejectedInsteadOfSilentlyDefaulting() {
        project.setSetting(CreativeStrategyPolicy.SETTING_KEY, Map.of("strategy", "UNKNOWN", "policyVersion", 1));
        assertThatThrownBy(() -> CreativeStrategyPolicy.from(project)).isInstanceOf(IllegalArgumentException.class);
        project.setSetting(CreativeStrategyPolicy.SETTING_KEY, Map.of("strategy", "STANDARD", "policyVersion", 2));
        assertThatThrownBy(() -> CreativeStrategyPolicy.from(project)).isInstanceOf(IllegalArgumentException.class);
        project.setSetting(CreativeStrategyPolicy.SETTING_KEY, Map.of("strategy", "STANDARD", "policyVersion", 1.5));
        assertThatThrownBy(() -> CreativeStrategyPolicy.from(project)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyFingerprintIgnoresUnrelatedProjectVersionButTracksStrategy() {
        var before = CreativeStrategyPolicy.from(project);
        ReflectionTestUtils.setField(project, "rowVersion", 5L);
        assertThat(CreativeStrategyPolicy.from(project)).isEqualTo(before);
        CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING).applyTo(project);
        assertThat(CreativeStrategyPolicy.from(project)).isNotEqualTo(before);
        assertThat(CreativeStrategyGuide.render(before))
                .contains("标准创作", "第一章仍执行大纲默认开篇要求", "不强制后续每章反转");
    }
}
