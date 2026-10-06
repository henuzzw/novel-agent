package com.novelagent.agent.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.agent.application.AutomationService;
import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AutomationControllerTest {
    private final AutomationService service = mock(AutomationService.class);
    private final UUID projectId = UUID.randomUUID();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AutomationController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void acceptsIdempotentTaskCreationAndRejectsMissingKeyAndInvalidRange() throws Exception {
        var run = AutomationRun.create(projectId, UUID.randomUUID(), 1, 3, ModelProvider.LOCAL_TEMPLATE, null);
        when(service.create(eq(projectId), any(), any())).thenReturn(AutomationRunResponse.from(run));
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID()).content("{\"firstChapter\":1,\"lastChapter\":3}"))
                .andExpect(status().isAccepted());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{\"firstChapter\":1,\"lastChapter\":3}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", UUID.randomUUID()).content("{\"firstChapter\":0,\"lastChapter\":3}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void forwardsQualityPolicyAndPreservesOmittedLegacyDefault() throws Exception {
        var run = AutomationRun.create(projectId, UUID.randomUUID(), 1, 1, ModelProvider.LOCAL_TEMPLATE, null);
        when(service.create(eq(projectId), any(), any())).thenReturn(AutomationRunResponse.from(run));
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID())
                .content("{\"firstChapter\":1,\"lastChapter\":1,\"qualityReviewEnabled\":true}"))
                .andExpect(status().isAccepted());
        var input = org.mockito.ArgumentCaptor.forClass(CreateAutomationRunRequest.class);
        org.mockito.Mockito.verify(service).create(eq(projectId), any(), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue().qualityReviewEnabled()).isTrue();
        org.assertj.core.api.Assertions.assertThat(new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"firstChapter\":1,\"lastChapter\":1}", CreateAutomationRunRequest.class).qualityReviewEnabled()).isFalse();
    }

    @Test
    void mapsOtherOwnerToNotFound() throws Exception {
        when(service.list(projectId)).thenThrow(new ProjectNotFoundException(projectId));
        mvc.perform(get(path())).andExpect(status().isNotFound());
    }

    @Test
    void rejectsOversizedInstructionBeforeServiceCall() throws Exception {
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID())
                .content("{\"firstChapter\":1,\"lastChapter\":3,\"instruction\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private String path() {
        return "/api/v1/projects/" + projectId + "/automation-runs";
    }

    @Test void validatesAndForwardsRevisionLimitsWithLegacyDefaults() throws Exception {
        when(service.create(eq(projectId), any(), any())).thenReturn(AutomationRunResponse.from(
                AutomationRun.create(projectId, UUID.randomUUID(), 1, 1, ModelProvider.LOCAL_TEMPLATE, null)));
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID())
                .content("{\"firstChapter\":1,\"lastChapter\":1,\"provider\":\"DEEPSEEK\",\"qualityReviewEnabled\":true,\"maxAutoRevisionRounds\":2,\"maxGenerationSteps\":30}"))
                .andExpect(status().isAccepted());
        var input = org.mockito.ArgumentCaptor.forClass(CreateAutomationRunRequest.class);
        org.mockito.Mockito.verify(service).create(eq(projectId), any(), input.capture());
        org.assertj.core.api.Assertions.assertThat(input.getValue().maxAutoRevisionRounds()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(input.getValue().generationStepLimit()).isEqualTo(30);
        var legacy = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue("{\"firstChapter\":1,\"lastChapter\":1}", CreateAutomationRunRequest.class);
        org.assertj.core.api.Assertions.assertThat(legacy.maxAutoRevisionRounds()).isZero();
        org.assertj.core.api.Assertions.assertThat(legacy.generationStepLimit()).isEqualTo(100);
        for (String invalid : new String[] {"\"maxAutoRevisionRounds\":4", "\"maxAutoRevisionRounds\":-1",
                "\"maxGenerationSteps\":0", "\"maxGenerationSteps\":501"}) {
            mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", UUID.randomUUID())
                    .content("{\"firstChapter\":1,\"lastChapter\":1," + invalid + "}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
