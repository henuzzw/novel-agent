package com.novelagent.project.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.CreativeStrategyService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeStrategy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CreativeStrategyControllerTest {
    @Test
    void readsAndValidatesStrategyVersionsAndUnknownValues() throws Exception {
        UUID id = UUID.randomUUID();
        String path = "/api/v1/projects/" + id + "/settings/creative-strategy";
        var service = mock(CreativeStrategyService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new CreativeStrategyController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(service.get(id)).thenReturn(new CreativeStrategyService.State(CreativeStrategy.STANDARD, 1, 2));
        when(service.update(any(), any(), anyLong())).thenThrow(new ResourceVersionConflictException(0, 2));
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.strategy").value("STANDARD"))
                .andExpect(jsonPath("$.policyVersion").value(1)).andExpect(jsonPath("$.version").value(2));
        mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"strategy\":\"FANQIE_GRIPPING\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"));
        for (String payload : new String[]{"{}", "{\"strategy\":\"STANDARD\"}",
                "{\"strategy\":\"UNKNOWN\",\"version\":2}", "{\"strategy\":\"STANDARD\",\"version\":-1}"}) {
            mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        }
        when(service.get(id)).thenThrow(new ProjectNotFoundException(id));
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }
}
