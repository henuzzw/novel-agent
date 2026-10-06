package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.agent.api.AgentRunSummary;
import com.novelagent.agent.infrastructure.AgentRunQueryRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentRunQueryServiceTest {
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AgentRunQueryRepository runs = mock(AgentRunQueryRepository.class);
    private final AgentRunQueryService service = new AgentRunQueryService(access, runs);

    @Test void allQueriesCheckOwnershipBeforeReadingRuns() {
        UUID id = UUID.randomUUID();
        doThrow(new ProjectNotFoundException(id)).when(access).requireOwnedProject(id);
        assertThatThrownBy(() -> service.list(id)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.summary(id)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.prompt(id, UUID.randomUUID())).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.requestSnapshot(id, UUID.randomUUID())).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(runs);
    }

    @Test void preservesEmptyResultsAndEstimatedSummary() {
        UUID id = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        var summary = new AgentRunSummary(0, 0, 0, 0, BigDecimal.ZERO, "CNY", "ESTIMATED");
        when(runs.list(id)).thenReturn(List.of());
        when(runs.summary(id)).thenReturn(summary);
        when(runs.prompt(id, run)).thenReturn(Optional.empty());
        when(runs.requestSnapshot(id, run)).thenReturn(Optional.empty());
        assertThat(service.list(id)).isEmpty();
        assertThat(service.summary(id)).isSameAs(summary);
        assertThat(service.prompt(id, run)).isEmpty();
        assertThat(service.requestSnapshot(id, run)).isEmpty();
    }
}
