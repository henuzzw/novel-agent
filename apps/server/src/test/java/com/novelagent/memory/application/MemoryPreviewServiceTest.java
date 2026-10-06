package com.novelagent.memory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.NovelProject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryPreviewServiceTest {
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final NovelMemoryService memory = mock(NovelMemoryService.class);
    private final ContextBudgetPlanner budgets = mock(ContextBudgetPlanner.class);
    private final MemoryPreviewService service = new MemoryPreviewService(access, memory, budgets);

    @Test void forwardsStageProviderQueryAndAuthoritativeCanonVersion() {
        UUID id = UUID.randomUUID();
        var project = mock(NovelProject.class);
        var budget = mock(MemoryBudgetPlan.class);
        var expected = new NovelMemoryContext(List.of(), List.of(), null);
        when(access.requireOwnedProject(id)).thenReturn(project);
        when(project.getCurrentCanonVersion()).thenReturn(4L);
        when(budgets.plan(AgentStage.CHAPTER_REVIEW, ModelProvider.DEEPSEEK, "线索")).thenReturn(budget);
        when(memory.recall(AgentStage.CHAPTER_REVIEW, id, 2, 4, "线索", budget)).thenReturn(expected);
        assertThat(service.preview(id, 2, "线索", AgentStage.CHAPTER_REVIEW, ModelProvider.DEEPSEEK)).isSameAs(expected);
        verify(memory).recall(AgentStage.CHAPTER_REVIEW, id, 2, 4, "线索", budget);
    }

    @Test void foreignProjectDoesNotAllocateBudgetOrRecallMemory() {
        UUID id = UUID.randomUUID();
        doThrow(new ProjectNotFoundException(id)).when(access).requireOwnedProject(id);
        assertThatThrownBy(() -> service.preview(id, 1, "线索", AgentStage.MANUSCRIPT, ModelProvider.LOCAL_CODEX))
                .isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(budgets, memory);
    }
}
