package com.novelagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.agent.application.AgentRunQueryService;
import com.novelagent.agent.application.AgentRunStreamService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentRunOutputControllerTest {
    @Test void outputIsPrivateNoStoreAndMissingRunIs404() {
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var queries = mock(AgentRunQueryService.class);
        var controller = new AgentRunOutputController(queries, new AgentRunStreamService(queries));
        var value = new AgentRunOutputResponse(run, "SUCCEEDED", "private response", false, null, null, null, 10L);
        when(queries.output(project, run)).thenReturn(Optional.of(value));
        var response = controller.response(project, run);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(response.getBody()).isEqualTo(value);
        when(queries.output(project, run)).thenReturn(Optional.empty());
        assertThat(controller.response(project, run).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.events(project, run).getStatusCode().value()).isEqualTo(404);
    }

    @Test void sseSendsInitialOutputAndFinalFailureThenEndsWithoutStartingAnyModel() throws Exception {
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var queries = mock(AgentRunQueryService.class);
        var streams = new AgentRunStreamService(queries);
        var mvc = MockMvcBuilders.standaloneSetup(new AgentRunOutputController(queries, streams)).build();
        when(queries.output(project, run)).thenReturn(Optional.of(
                new AgentRunOutputResponse(run, "RUNNING", "partial", false, null, null, null, null)));
        var request = mvc.perform(get("/api/v1/projects/{project}/agent-runs/{run}/events", project, run))
                .andExpect(status().isOk()).andReturn();
        assertThat(request.getRequest().isAsyncStarted()).isTrue();
        when(queries.output(project, run)).thenReturn(Optional.of(new AgentRunOutputResponse(run, "FAILED", "partial",
                false, "CodexAppServerException", "TIMEOUT", "等待上限 600 秒", 600_000L)));
        streams.tick();
        var response = mvc.perform(asyncDispatch(request)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).contains("text/event-stream");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("event:output", "partial", "TIMEOUT", "600 秒");
        org.mockito.Mockito.clearInvocations(queries);
        streams.tick();
        org.mockito.Mockito.verifyNoInteractions(queries);
    }
}
