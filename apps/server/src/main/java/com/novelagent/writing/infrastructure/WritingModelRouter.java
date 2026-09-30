package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexAgentSession;
import com.novelagent.planning.infrastructure.CodexAgentSessionRepository;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class WritingModelRouter {
    private final CodexAppServerClient codex;
    private final CodexAgentSessionRepository sessions;
    private final DeepSeekStructuredOutputClient deepSeek;
    private final AgentRunRecorder runs;

    WritingModelRouter(CodexAppServerClient codex, CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek, AgentRunRecorder runs) {
        this.codex = codex;
        this.sessions = sessions;
        this.deepSeek = deepSeek;
        this.runs = runs;
    }

    String request(UUID projectId, String workflow, ModelProvider provider,
            WritingPromptFactory.Prompt prompt, JsonNode schema, String schemaName, int maxTokens) {
        if (provider == ModelProvider.DEEPSEEK) {
            return runs.record(projectId, workflow, provider, prompt.system(), prompt.user(),
                    () -> deepSeek.request(schemaName, prompt.system(), prompt.user(), schema, maxTokens));
        }
        if (provider != ModelProvider.LOCAL_CODEX) {
            throw new IllegalArgumentException("不支持的生成模型：" + provider);
        }
        CodexAgentSession session = sessions.findByProjectIdAndWorkflowType(projectId, workflow).orElse(null);
        if (session == null) {
            session = sessions.saveAndFlush(CodexAgentSession.create(
                    projectId, workflow, codex.startThread(projectId, prompt.system())));
        } else {
            try {
                codex.resumeThread(session.getThreadId(), projectId, prompt.system());
            }
            catch (com.novelagent.planning.infrastructure.CodexAppServerException exception) {
                if (!exception.indicatesMissingThread()) {
                    throw exception;
                }
                session.replaceThread(codex.startThread(projectId, prompt.system()));
                session = sessions.saveAndFlush(session);
            }
        }
        CodexAgentSession activeSession = session;
        CodexAppServerClient.TurnResult result = runs.record(projectId, workflow, provider,
                prompt.system(), prompt.user(),
                () -> codex.runStructuredTurn(activeSession.getThreadId(), projectId, prompt.user(), schema));
        session.recordTurn(result.turnId());
        sessions.saveAndFlush(session);
        return result.output();
    }
}
