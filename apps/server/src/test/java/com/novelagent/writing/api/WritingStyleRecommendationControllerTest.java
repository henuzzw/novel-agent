package com.novelagent.writing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.application.WritingStyleAnalysisService;
import com.novelagent.writing.application.WritingStylePreviewService;
import com.novelagent.writing.application.WritingStyleRecommendationService;
import com.novelagent.writing.application.WritingStyleService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WritingStyleRecommendationControllerTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID bibleId = UUID.randomUUID();
    private final WritingStyleRecommendationService service = mock(WritingStyleRecommendationService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WritingStyleController(mock(WritingStyleService.class),
            mock(WritingStyleAnalysisService.class), mock(WritingStylePreviewService.class), service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final String path = "/api/v1/projects/" + projectId + "/writing-style/actions/recommend";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsRecommendationSnapshotWithoutApplyingStyle() throws Exception {
        when(service.recommend(any(), any())).thenReturn(new WritingStyleRecommendationResponse(bibleId, 0, 2,
                ModelProvider.LOCAL_TEMPLATE, "TEMPLATE", "本地模板不判断", List.of()));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceBibleVersionId").value(bibleId.toString()))
                .andExpect(jsonPath("$.recommendationMode").value("TEMPLATE"));
    }

    @Test
    void rejectsMissingFieldsNegativeVersionAndOversizedInstruction() throws Exception {
        for (var input : List.of("{}", mapper.writeValueAsString(new RecommendWritingStyleRequest(bibleId, -1L,
                ModelProvider.LOCAL_TEMPLATE, null)), mapper.writeValueAsString(new RecommendWritingStyleRequest(bibleId, 0L,
                ModelProvider.LOCAL_TEMPLATE, "字".repeat(1001))))) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void surfacesConflictAndDeniedOwnership() throws Exception {
        when(service.recommend(any(), any())).thenThrow(new ResourceVersionConflictException(0, 1));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input())).andExpect(status().isConflict());
        doThrow(new ProjectNotFoundException(projectId)).when(service).recommend(any(), any());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(input())).andExpect(status().isNotFound());
    }

    private String input() throws Exception {
        return mapper.writeValueAsString(new RecommendWritingStyleRequest(bibleId, 0L, ModelProvider.LOCAL_TEMPLATE, null));
    }
}
