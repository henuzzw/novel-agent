package com.novelagent.agent.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.novelagent.agent.application.GenerationControlRegistry;
import com.novelagent.agent.application.GenerationStopConflictException;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GenerationControlControllerTest {
    @Test void ownershipIsRequiredAndFinishedCallsReturnConflict() throws Exception {
        var access = mock(ProjectAccessService.class);
        var controls = mock(GenerationControlRegistry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GenerationControlController(access, controls))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        UUID project = UUID.randomUUID(), id = UUID.randomUUID();
        mvc.perform(post("/api/v1/projects/{p}/generation-requests/{r}/actions/stop", project, id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("STOP_REQUESTED"));
        var order = inOrder(access, controls);
        order.verify(access).requireOwnedProject(project);
        order.verify(controls).stopRequest(project, id);
        doThrow(new GenerationStopConflictException("已经结束")).when(controls).stopRun(project, id);
        mvc.perform(post("/api/v1/projects/{p}/agent-runs/{r}/actions/stop", project, id))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GENERATION_STOP_CONFLICT"));
        clearInvocations(controls);
        doThrow(new ProjectNotFoundException(project)).when(access).requireOwnedProject(project);
        mvc.perform(post("/api/v1/projects/{p}/agent-runs/{r}/actions/stop", project, id)).andExpect(status().isNotFound());
        verifyNoInteractions(controls);
    }

    @Test void cancelledGenerationHasDistinctProblemCode() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StoppedController()).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/stopped")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENERATION_CANCELLED"));
    }

    @org.springframework.web.bind.annotation.RestController
    static class StoppedController {
        @org.springframework.web.bind.annotation.PostMapping("/stopped")
        public void stop() { throw new GenerationStoppedException(); }
    }
}
