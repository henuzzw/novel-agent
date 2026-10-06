package com.novelagent.writing.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.writing.application.ManuscriptLocalEditConflictException;
import com.novelagent.writing.application.ManuscriptLocalEditService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ManuscriptLocalEditControllerTest {
    private final ManuscriptLocalEditService service = mock(ManuscriptLocalEditService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ManuscriptLocalEditController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final UUID source = UUID.randomUUID();
    private final String path = "/api/v1/projects/" + UUID.randomUUID() + "/chapters/1/manuscripts/actions/local-edit";
    private final String request = "{\"sourceManuscriptId\":\"" + source + "\",\"sourceRowVersion\":0,"
            + "\"selection\":\"old\",\"occurrence\":1,\"offset\":0,\"provider\":\"LOCAL_TEMPLATE\","
            + "\"instruction\":\"clarify\",\"authorized\":true}";

    @Test
    void returnsTemplateUnsupportedStatusWithoutManuscript() throws Exception {
        when(service.edit(any(), anyInt(), any())).thenReturn(new ManuscriptLocalEditResponse("NOT_ASSESSED",
                "unsupported", source, 0, "old", 1, 0, null, null));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assessment").value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.manuscript").doesNotExist());
    }

    @Test
    void rejectsMissingExplicitRowVersionBeforeService() throws Exception {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request.replace("\"sourceRowVersion\":0,", "")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void mapsChangedWritingBasisTo409() throws Exception {
        when(service.edit(any(), anyInt(), any())).thenThrow(new ManuscriptLocalEditConflictException("basis changed"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MANUSCRIPT_LOCAL_EDIT_STALE"));
    }
}
