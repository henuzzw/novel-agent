package com.novelagent.writing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.writing.application.WritingStyleAnalysisService;
import com.novelagent.writing.application.WritingStylePresets;
import com.novelagent.writing.application.WritingStylePreviewService;
import com.novelagent.writing.application.WritingStyleRecommendationService;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WritingStylePreviewControllerTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID outlineId = UUID.randomUUID();
    private final WritingStylePreviewService previews = mock(WritingStylePreviewService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsIndependentPreviewAndSourceVersion() throws Exception {
        var profile = WritingStylePresets.all().getFirst();
        var response = new WritingStylePreviewResponse(outlineId, 2, 3, UUID.randomUUID(), profile,
                ModelProvider.LOCAL_TEMPLATE, 800, "TEMPLATE", new WritingStylePreviewContent("开头", "独立预览"));
        when(previews.generate(any(), any())).thenReturn(response);
        var mvc = MockMvcBuilders.standaloneSetup(new WritingStyleController(mock(WritingStyleService.class),
                mock(WritingStyleAnalysisService.class), previews, mock(WritingStyleRecommendationService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var request = new GenerateStylePreviewRequest(outlineId, 2L, profile, ModelProvider.LOCAL_TEMPLATE, 800, null);
        mvc.perform(post("/api/v1/projects/" + projectId + "/writing-style/actions/preview")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceOutlineRowVersion").value(2))
                .andExpect(jsonPath("$.previewMode").value("TEMPLATE"))
                .andExpect(jsonPath("$.content.body").value("独立预览"));
    }

    @Test
    void rejectsMissingFieldsAndInvalidLengthBeforeCallingService() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new WritingStyleController(mock(WritingStyleService.class),
                mock(WritingStyleAnalysisService.class), previews, mock(WritingStyleRecommendationService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/v1/projects/" + projectId + "/writing-style/actions/preview";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var invalid = new GenerateStylePreviewRequest(outlineId, 0L, WritingStylePresets.all().getFirst(),
                ModelProvider.LOCAL_TEMPLATE, 200, null);
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(previews);
    }
}
