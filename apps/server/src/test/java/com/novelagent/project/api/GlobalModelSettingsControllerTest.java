package com.novelagent.project.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.GlobalModelCatalogService;
import com.novelagent.project.application.GlobalModelSettingsService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.GlobalModelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GlobalModelSettingsControllerTest {
    @Test void returnsSettingsAndRejectsStaleAndMalformedUpdates() throws Exception {
        var settings = mock(GlobalModelSettingsService.class);
        var catalog = mock(GlobalModelCatalogService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new GlobalModelSettingsController(settings, catalog))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        when(settings.get()).thenReturn(new GlobalModelSettings(ModelProvider.DEEPSEEK,
                "gpt-6-sol", "high", "deepseek-v4-pro", 2));
        when(catalog.validate(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(settings.update(any())).thenThrow(new ResourceVersionConflictException(0, 2));
        mvc.perform(get("/api/v1/settings/model")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deepSeekModel").value("deepseek-v4-pro"));
        mvc.perform(put("/api/v1/settings/model").contentType(MediaType.APPLICATION_JSON).content("""
                {"provider":"DEEPSEEK","codexModel":"gpt-6-sol","codexEffort":"high","deepSeekModel":"deepseek-flash","version":0}
                """)).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/settings/model").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
