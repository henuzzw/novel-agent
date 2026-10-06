package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.canon.infrastructure.ProjectionStatusRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.NovelProject;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionStatusServiceTest {
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final ProjectionStatusRepository projections = mock(ProjectionStatusRepository.class);
    private final ProjectionStatusService service = new ProjectionStatusService(access, projections);

    @Test void usesCurrentCanonVersionAndPreservesIndependentProjectionFlags() {
        UUID id = UUID.randomUUID();
        var project = mock(NovelProject.class);
        when(access.requireOwnedProject(id)).thenReturn(project);
        when(project.getCurrentCanonVersion()).thenReturn(3L);
        when(projections.isPublished(id, 3)).thenReturn(true);
        when(projections.isProjected(id, 3, "PGVECTOR")).thenReturn(true);
        var status = service.status(id);
        assertThat(status.canonVersion()).isEqualTo(3);
        assertThat(status.kafkaPublished()).isTrue();
        assertThat(status.pgvectorProjected()).isTrue();
        assertThat(status.neo4jProjected()).isFalse();
    }

    @Test void foreignProjectStopsBeforeProjectionQueries() {
        UUID id = UUID.randomUUID();
        doThrow(new ProjectNotFoundException(id)).when(access).requireOwnedProject(id);
        assertThatThrownBy(() -> service.status(id)).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(projections);
    }
}
