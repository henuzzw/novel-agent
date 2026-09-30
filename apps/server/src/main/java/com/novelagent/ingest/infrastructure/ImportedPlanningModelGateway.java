package com.novelagent.ingest.infrastructure;

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
public class ImportedPlanningModelGateway {
    private final CodexAppServerClient codex;
    private final CodexAgentSessionRepository sessions;
    private final DeepSeekStructuredOutputClient deepSeek;
    private final AgentRunRecorder runs;

    public ImportedPlanningModelGateway(CodexAppServerClient codex, CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek, AgentRunRecorder runs) {
        this.codex = codex;
        this.sessions = sessions;
        this.deepSeek = deepSeek;
        this.runs = runs;
    }

    public String request(UUID projectId, String workflow, ModelProvider provider, String systemPrompt,
            String userPrompt, JsonNode schema, String schemaName, int maxTokens) {
        if (provider == ModelProvider.DEEPSEEK) {
            return runs.record(projectId, workflow, provider, systemPrompt, userPrompt,
                    () -> deepSeek.request(schemaName, systemPrompt, userPrompt, schema, maxTokens));
        }
        if (provider != ModelProvider.LOCAL_CODEX) {
            throw new IllegalArgumentException("导入反推规划请选择服务端 Codex 或 DeepSeek");
        }
        CodexAgentSession session = sessions.findByProjectIdAndWorkflowType(projectId, workflow).orElse(null);
        if (session == null) {
            session = sessions.saveAndFlush(CodexAgentSession.create(projectId, workflow,
                    codex.startThread(projectId, systemPrompt)));
        } else {
            try {
                codex.resumeThread(session.getThreadId(), projectId, systemPrompt);
            }
            catch (com.novelagent.planning.infrastructure.CodexAppServerException exception) {
                if (!exception.indicatesMissingThread()) {
                    throw exception;
                }
                session.replaceThread(codex.startThread(projectId, systemPrompt));
                session = sessions.saveAndFlush(session);
            }
        }
        CodexAgentSession activeSession = session;
        CodexAppServerClient.TurnResult result = runs.record(projectId, workflow, provider,
                systemPrompt, userPrompt,
                () -> codex.runStructuredTurn(activeSession.getThreadId(), projectId, userPrompt, schema));
        session.recordTurn(result.turnId());
        sessions.saveAndFlush(session);
        return result.output();
    }
}
