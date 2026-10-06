package com.novelagent.writing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.writing.application.StylePreviewEditingService;
import com.novelagent.writing.application.WritingStylePresets;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StylePreviewEditingControllerTest {
    private final StylePreviewEditingService service = mock(StylePreviewEditingService.class);
    private final String base = "/api/v1/projects/" + UUID.randomUUID() + "/writing-style";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesSourceAndSelectionBeforeInvokingModels() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new StylePreviewEditingController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post(base + "/actions/check-preview").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var invalid = new CheckStylePreviewRequest(new GenerateStylePreviewRequest(UUID.randomUUID(), 0L,
                WritingStylePresets.all().getFirst(), ModelProvider.DEEPSEEK, 200, null), new WritingStylePreviewContent("标题", "正文"));
        mvc.perform(post(base + "/actions/check-preview").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
        mvc.perform(post(base + "/preview-reviews/" + UUID.randomUUID() + "/actions/revise")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new ReviseStylePreviewRequest(ModelProvider.DEEPSEEK, List.of(), null))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void returnsRevisionCandidateWithoutTouchingStyleApplication() throws Exception {
        when(service.revise(any(), any(), any())).thenReturn(new WritingStylePreviewResponse(UUID.randomUUID(), 2, 1,
                UUID.randomUUID(), WritingStylePresets.all().getFirst(), ModelProvider.DEEPSEEK, 800, "MODEL",
                new WritingStylePreviewContent("标题", "保留原稿的新候选")));
        var mvc = MockMvcBuilders.standaloneSetup(new StylePreviewEditingController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post(base + "/preview-reviews/" + UUID.randomUUID() + "/actions/revise")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(new ReviseStylePreviewRequest(ModelProvider.DEEPSEEK, List.of("E1"), null))))
                .andExpect(status().isOk());
    }
}
