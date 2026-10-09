package com.novelagent.prompt.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.prompt.application.AgentPromptService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AgentPromptControllerTest {
    @Test void readsCatalogAndRejectsStaleInvalidOrMissingVersion() throws Exception {
        var service = mock(AgentPromptService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AgentPromptController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        var value = new AgentPromptService.View("OUTLINE", "OUTLINE", "大纲", "规划", "指令", "", "默认", "边界", true, 2, null);
        when(service.list()).thenReturn(List.of(value));
        when(service.get("OUTLINE")).thenReturn(value);
        mvc.perform(get("/api/v1/settings/prompts")).andExpect(status().isOk()).andExpect(jsonPath("$[0].version").value(2));
        mvc.perform(get("/api/v1/settings/prompts/OUTLINE")).andExpect(status().isOk()).andExpect(jsonPath("$.systemPrompt").value("指令"));
        mvc.perform(get("/api/v1/settings/prompts/OUTLINE")).andExpect(jsonPath("$.sessionSystemPrompt").isNotEmpty());
        when(service.save(eq("OUTLINE"), anyString(), anyString(), anyString(), eq(0L))).thenThrow(new ResourceVersionConflictException(0, 2));
        mvc.perform(put("/api/v1/settings/prompts/OUTLINE").contentType(MediaType.APPLICATION_JSON)
                .content("{\"systemPrompt\":\"修改\",\"sessionSystemPrompt\":\"系统角色\",\"guidance\":\"\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_VERSION_CONFLICT"));
        for (String payload : List.of("{}", "{\"systemPrompt\":\"修改\",\"guidance\":\"\"}",
                "{\"systemPrompt\":\"  \",\"guidance\":\"\",\"version\":0}",
                "{\"systemPrompt\":\"修改\",\"guidance\":\"\",\"version\":-1}")) {
            mvc.perform(put("/api/v1/settings/prompts/OUTLINE").contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/settings/prompts/OUTLINE/reset").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test void resetAndHistoryDelegateToTheCurrentUserService() throws Exception {
        var service = mock(AgentPromptService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AgentPromptController(service)).build();
        when(service.history("MANUSCRIPT")).thenReturn(List.of());
        mvc.perform(post("/api/v1/settings/prompts/MANUSCRIPT/reset").contentType(MediaType.APPLICATION_JSON).content("{\"version\":1}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/settings/prompts/MANUSCRIPT/history")).andExpect(status().isOk());
        verify(service).reset("MANUSCRIPT", 1);
        verify(service).history("MANUSCRIPT");
    }
}
