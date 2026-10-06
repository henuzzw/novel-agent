package com.novelagent.ingest.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ProjectNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImportAnalysisControllerTest {
    private final ImportAnalysisStore store = mock(ImportAnalysisStore.class);
    private final ImportAnalysisRunner runner = mock(ImportAnalysisRunner.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ImportAnalysisController(store, runner)).setControllerAdvice(new ApiExceptionHandler()).build();
    private final UUID project = UUID.randomUUID(), source = UUID.randomUUID(), report = UUID.randomUUID();
    private String root() { return "/api/v1/projects/" + project + "/imports/" + source + "/analyses"; }
    @Test void readIsPrivateNoStoreAndNeverStartsModelCalls() throws Exception {
        when(store.list(project, source)).thenReturn(List.of()); mvc.perform(get(root())).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")); verifyNoInteractions(runner);
    }
    @Test void inaccessibleProjectReturns404() throws Exception {
        when(store.list(project, source)).thenThrow(new ProjectNotFoundException(project)); mvc.perform(get(root())).andExpect(status().isNotFound()); verifyNoInteractions(runner);
    }
    @Test void missingVersionCannotTriggerPaidAnalysis() throws Exception {
        mvc.perform(post(root() + "/" + report + "/actions/run-next").contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isBadRequest()); verifyNoInteractions(runner);
    }
}
