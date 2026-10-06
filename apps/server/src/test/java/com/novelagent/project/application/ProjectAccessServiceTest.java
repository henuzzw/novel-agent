package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectAccessServiceTest {
    @Test void returnsOwnedProjectAndTreatsMissingOrForeignProjectAsNotFound() {
        UUID owner = UUID.randomUUID();
        var project = NovelProject.create(UUID.randomUUID(), owner, "测试项目", EntryMode.IDEA);
        var foreign = NovelProject.create(UUID.randomUUID(), UUID.randomUUID(), "其他作者", EntryMode.IDEA);
        var projects = mock(NovelProjectRepository.class);
        when(projects.findById(project.getId())).thenReturn(Optional.of(project));
        when(projects.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        var service = new ProjectAccessService(projects, new CurrentActorProvider(owner));
        assertThat(service.requireOwnedProject(project.getId())).isSameAs(project);
        assertThatThrownBy(() -> service.requireOwnedProject(foreign.getId())).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.requireOwnedProject(UUID.randomUUID())).isInstanceOf(ProjectNotFoundException.class);
    }
}
