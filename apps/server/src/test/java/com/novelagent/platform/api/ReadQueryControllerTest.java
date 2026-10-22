package com.novelagent.platform.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.agent.api.AgentRunController;
import com.novelagent.agent.api.dto.AgentRunPromptResponse;
import com.novelagent.agent.api.dto.AgentRunSummary;
import com.novelagent.agent.api.dto.AgentTokenUsage;
import com.novelagent.agent.application.AgentRunQueryService;
import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.api.ProjectionStatusController;
import com.novelagent.canon.api.dto.ProjectionStatus;
import com.novelagent.canon.application.ProjectionStatusService;
import com.novelagent.memory.api.MemoryController;
import com.novelagent.memory.application.MemoryPreviewService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReadQueryControllerTest {
    @Test void agentQueriesKeepJsonFieldsPromptNoStoreAndMissingPrompt404() throws Exception {
        UUID id = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var service = mock(AgentRunQueryService.class);
        when(service.list(id)).thenReturn(List.of());
        when(service.summary(id)).thenReturn(new AgentRunSummary(2, 1, 100, 50, new BigDecimal("0.01"), "CNY", "ESTIMATED"));
        when(service.prompt(id, runId)).thenReturn(Optional.of(new AgentRunPromptResponse(runId, "system", "user", "preview")));
        var mvc = MockMvcBuilders.standaloneSetup(new AgentRunController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/v1/projects/" + id + "/agent-runs";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get(path + "/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.calls").value(2))
                .andExpect(jsonPath("$.failures").value(1)).andExpect(jsonPath("$.inputTokens").value(100))
                .andExpect(jsonPath("$.outputTokens").value(50)).andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.tokenSource").value("ESTIMATED")).andExpect(jsonPath("$.estimatedCost").value(0.01));
        mvc.perform(get(path + "/" + runId + "/prompt")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.userPrompt").value("user"))
                .andExpect(jsonPath("$.systemPrompt").value("system")).andExpect(jsonPath("$.promptPreview").value("preview"));
        when(service.prompt(id, runId)).thenReturn(Optional.empty());
        mvc.perform(get(path + "/" + runId + "/prompt")).andExpect(status().isNotFound());
        when(service.list(id)).thenThrow(new ProjectNotFoundException(id));
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test void projectionQueryKeepsItsResponseShapeAndOwnerErrors() throws Exception {
        UUID id = UUID.randomUUID();
        var service = mock(ProjectionStatusService.class);
        when(service.status(id)).thenReturn(new ProjectionStatus(2, true, false, true));
        var mvc = MockMvcBuilders.standaloneSetup(new ProjectionStatusController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/v1/projects/" + id + "/projection-status";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.canonVersion").value(2))
                .andExpect(jsonPath("$.kafkaPublished").value(true)).andExpect(jsonPath("$.pgvectorProjected").value(false))
                .andExpect(jsonPath("$.neo4jProjected").value(true));
        when(service.status(id)).thenThrow(new ProjectNotFoundException(id));
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test void tokenUsageReturnsBothPeriodsWithoutCachingAndKeepsOwnerErrors() throws Exception {
        UUID id = UUID.randomUUID();
        var service = mock(AgentRunQueryService.class);
        var now = OffsetDateTime.parse("2026-10-13T08:00:00Z");
        var total = new AgentRunSummary(120, 2, 1000, 50, BigDecimal.ZERO, "CNY", "MIXED");
        var recent = new AgentRunSummary(10, 1, 100, 5, BigDecimal.ZERO, "CNY", "ESTIMATED");
        when(service.usage(id)).thenReturn(new AgentTokenUsage(now, now.minusHours(5), total, recent));
        var mvc = MockMvcBuilders.standaloneSetup(new AgentRunController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/v1/projects/" + id + "/agent-runs/usage";
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.projectTotal.calls").value(120))
                .andExpect(jsonPath("$.lastFiveHours.calls").value(10))
                .andExpect(jsonPath("$.projectTotal.actualCalls").value(0))
                .andExpect(jsonPath("$.recentFrom").exists()).andExpect(jsonPath("$.asOf").exists());
        when(service.usage(id)).thenThrow(new ProjectNotFoundException(id));
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test void memoryPreviewKeepsDefaultAndExplicitQueryParameters() throws Exception {
        UUID id = UUID.randomUUID();
        var service = mock(MemoryPreviewService.class);
        when(service.preview(any(), ArgumentMatchers.anyInt(), any(), any(), any()))
                .thenReturn(new NovelMemoryContext(List.of(), List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new MemoryController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        String path = "/api/v1/projects/" + id + "/memory/preview";
        mvc.perform(get(path).param("chapterNumber", "1").param("query", "线索")).andExpect(status().isOk())
                .andExpect(jsonPath("$.semanticMemories").isArray()).andExpect(jsonPath("$.graphFacts").isArray());
        verify(service).preview(id, 1, "线索", AgentStage.MANUSCRIPT, ModelProvider.LOCAL_CODEX);
        mvc.perform(get(path).param("chapterNumber", "2").param("query", "线索").param("stage", "CHAPTER_REVIEW")
                .param("provider", "DEEPSEEK")).andExpect(status().isOk());
        verify(service).preview(id, 2, "线索", AgentStage.CHAPTER_REVIEW, ModelProvider.DEEPSEEK);
        mvc.perform(get(path).param("chapterNumber", "2")).andExpect(status().isBadRequest());
    }
}
