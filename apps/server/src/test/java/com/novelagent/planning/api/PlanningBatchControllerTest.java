package com.novelagent.planning.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.planning.application.PlanningBatchRunner;
import com.novelagent.planning.application.PlanningBatchService;
import com.novelagent.platform.api.ApiExceptionHandler;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlanningBatchControllerTest {
    private final PlanningBatchService batches = mock(PlanningBatchService.class);
    private final PlanningBatchRunner runner = mock(PlanningBatchRunner.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlanningBatchController(batches, runner))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final UUID project = UUID.randomUUID();
    private final UUID batch = UUID.randomUUID();

    @Test void advancingRequiresAnExplicitNonnegativeVersion() throws Exception {
        String path = "/api/v1/projects/" + project + "/planning-batches/" + batch + "/actions/run-next";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"version\":-1}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runner, batches);
    }

    @Test void oneActionDelegatesOnlyOneRun() throws Exception {
        mvc.perform(post("/api/v1/projects/" + project + "/planning-batches/" + batch + "/actions/run-next")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
                .andExpect(status().isOk());
        verify(runner).runNext(project, batch, 2);
        verifyNoInteractions(batches);
    }

    @Test void templateAndMissingBibleIdentityCannotCreateAPlanningBatch() throws Exception {
        String template = """
                {"chapterTo":4,"chunkSize":2,"provider":"LOCAL_TEMPLATE","instruction":"",
                 "requestId":"%s","expectedBibleVersion":0,"expectedBibleId":"%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(post("/api/v1/projects/" + project + "/planning-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(template))
                .andExpect(status().isBadRequest());
        String missingId = template.replace("LOCAL_TEMPLATE", "DEEPSEEK")
                .replaceAll(",\"expectedBibleId\":\"[^\"]+\"", "");
        mvc.perform(post("/api/v1/projects/" + project + "/planning-batches")
                        .contentType(MediaType.APPLICATION_JSON).content(missingId))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(batches, runner);
    }
}
