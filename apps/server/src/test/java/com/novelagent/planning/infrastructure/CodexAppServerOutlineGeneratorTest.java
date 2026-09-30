package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class CodexAppServerOutlineGeneratorTest {

    @Test
    void startsFreshThreadInsteadOfResumingPreviousOutlineHistory() {
        UUID projectId = UUID.randomUUID();
        CodexAppServerClient client = mock(CodexAppServerClient.class);
        CodexAgentSessionRepository sessions = mock(CodexAgentSessionRepository.class);
        OutlineModelOutputParser parser = mock(OutlineModelOutputParser.class);
        AgentRunRecorder runs = mock(AgentRunRecorder.class);
        CodexAgentSession existing = CodexAgentSession.create(projectId, "OUTLINE", "older-thread");
        OutlineContent selected = new OutlineContent("选定基准", "前提", "结构", "节奏",
                110_000, 130_000, List.of());
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(),
                "主角", "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000,
                3, 40, 3_000, 2_400, 3_600);
        GeneratedOutline expected = new GeneratedOutline("LOCAL_CODEX", selected, List.of());
        when(sessions.findByProjectIdAndWorkflowType(projectId, "OUTLINE")).thenReturn(Optional.of(existing));
        when(client.startThread(eq(projectId), anyString())).thenReturn("fresh-thread");
        when(sessions.saveAndFlush(existing)).thenReturn(existing);
        when(client.runStructuredTurn(eq("fresh-thread"), eq(projectId), anyString(), any()))
                .thenReturn(new CodexAppServerClient.TurnResult("turn-1", "model-output"));
        when(runs.record(eq(projectId), eq("OUTLINE"), eq(ModelProvider.LOCAL_CODEX),
                anyString(), anyString(), any())).thenAnswer(call ->
                ((Supplier<?>) call.getArgument(5)).get());
        when(parser.parse(ModelProvider.LOCAL_CODEX, "model-output")).thenReturn(expected);
        CodexAppServerOutlineGenerator generator = new CodexAppServerOutlineGenerator(client, sessions,
                new OutlineModelPromptFactory(new ObjectMapper()), parser,
                new OutlineOutputSchema(new ObjectMapper()), runs);

        assertThat(generator.generate(projectId, bible, budget, selected, "微调")).isSameAs(expected);
        assertThat(existing.getThreadId()).isEqualTo("fresh-thread");
        verify(client, never()).resumeThread(anyString(), any(), anyString());
    }
}
