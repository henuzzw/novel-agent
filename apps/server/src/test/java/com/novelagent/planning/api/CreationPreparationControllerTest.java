package com.novelagent.planning.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.novelagent.planning.application.CreationPreparationApprovalService;
import com.novelagent.planning.application.CreationPreparationContextService;
import com.novelagent.planning.application.CreationPreparationRunner;
import com.novelagent.planning.application.CreationPreparationStore;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ProjectNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CreationPreparationControllerTest {
    private final CreationPreparationStore store = mock(CreationPreparationStore.class);
    private final CreationPreparationRunner runner = mock(CreationPreparationRunner.class);
    private final CreationPreparationApprovalService approval = mock(CreationPreparationApprovalService.class);
    private final CreationPreparationContextService context = mock(CreationPreparationContextService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new CreationPreparationController(store, runner, approval, context))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final UUID project = UUID.randomUUID(), task = UUID.randomUUID();
    private String root() { return "/api/v1/projects/" + project + "/creation-preparations"; }
    @Test void readingTasksCheckpointsAndLinksDoesNotGenerateOrApplyAnything() throws Exception {
        when(store.list(project)).thenReturn(List.of()); when(context.checkpoints(project)).thenReturn(List.of()); when(context.links(project)).thenReturn(List.of());
        mvc.perform(get(root())).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(root() + "/checkpoints")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(root() + "/plan-links")).andExpect(status().isOk());
        verifyNoInteractions(runner, approval);
    }
    @Test void missingVersionIsRejectedBeforeModelExecution() throws Exception {
        mvc.perform(post(root() + "/" + task + "/actions/run-next").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(runner);
    }
    @Test void inaccessibleProjectReturns404ForTasksAndAssociatedFacts() throws Exception {
        when(store.list(project)).thenThrow(new ProjectNotFoundException(project)); when(context.links(project)).thenThrow(new ProjectNotFoundException(project));
        mvc.perform(get(root())).andExpect(status().isNotFound()); mvc.perform(get(root() + "/plan-links")).andExpect(status().isNotFound());
    }
}
