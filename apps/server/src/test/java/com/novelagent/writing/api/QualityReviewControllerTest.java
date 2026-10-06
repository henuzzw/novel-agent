package com.novelagent.writing.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.api.ReviseQualityRequest.Scope;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class QualityReviewControllerTest {
    private final QualityReviewService service = mock(QualityReviewService.class);
    private final UUID project = UUID.randomUUID();
    private final UUID report = UUID.randomUUID();
    private final String url = "/api/v1/projects/" + project + "/chapters/1/quality-reviews/" + report + "/actions/revise";
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new QualityReviewController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test void oldRequestDefaultsToExpressionOnly() throws Exception {
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\",\"issueIds\":[\"Q1\"]}"))
                .andExpect(status().isCreated());
        verify(service).revise(project, 1, report, List.of("Q1"), ModelProvider.DEEPSEEK, null, Scope.EXPRESSION_ONLY);
    }

    @Test void explicitlyPassesSceneScopeAndAuthorInstruction() throws Exception {
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\",\"issueIds\":[\"Q1\"],\"instruction\":\"调整段落\",\"scope\":\"SCENE_STRUCTURE\"}"))
                .andExpect(status().isCreated());
        verify(service).revise(project, 1, report, List.of("Q1"), ModelProvider.DEEPSEEK, "调整段落", Scope.SCENE_STRUCTURE);
    }

    @Test void rejectsUnknownAndNumericScopesAndInvalidSelectionsBeforeService() throws Exception {
        for (String body : List.of("{\"issueIds\":[\"Q1\"],\"scope\":\"ALL_FACTS\"}",
                "{\"issueIds\":[\"Q1\"],\"scope\":1}", "{}", "{\"issueIds\":[]}", "{\"issueIds\":[\" \"]}",
                "{\"issueIds\":[\"Q1\"],\"instruction\":\"" + "a".repeat(2001) + "\"}")) {
            mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
