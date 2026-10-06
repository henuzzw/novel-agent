package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.RequestSnapshot;
import com.novelagent.agent.application.AgentRunRecorder.ContextBudget;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class StructuredModelGateway {
    private final CodexAppServerClient codex;
    private final CodexAgentSessionRepository sessions;
    private final DeepSeekStructuredOutputClient deepSeek;
    private final AgentRunRecorder runs;
    private final ModelContextProperties context;

    public StructuredModelGateway(CodexAppServerClient codex, CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek, AgentRunRecorder runs) {
        this(codex, sessions, deepSeek, runs, new ModelContextProperties());
    }

    @Autowired
    public StructuredModelGateway(CodexAppServerClient codex, CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek, AgentRunRecorder runs, ModelContextProperties context) {
        this.codex = codex;
        this.sessions = sessions;
        this.deepSeek = deepSeek;
        this.runs = runs;
        this.context = context;
    }

    public String request(UUID projectId, String workflow, ModelProvider provider,
            String systemPrompt, String userPrompt, JsonNode schema, String schemaName,
            int maxOutputTokens, CodexSessionPolicy sessionPolicy) {
        return request(projectId, workflow, provider, systemPrompt, userPrompt, schema, schemaName,
                maxOutputTokens, sessionPolicy, null);
    }

    public String request(UUID projectId, String workflow, ModelProvider provider,
            String systemPrompt, String userPrompt, JsonNode schema, String schemaName,
            int maxOutputTokens, CodexSessionPolicy sessionPolicy, Consumer<String> processOutput) {
        if (provider != ModelProvider.DEEPSEEK && provider != ModelProvider.LOCAL_CODEX) {
            throw new IllegalArgumentException("当前模型调用只支持服务端 Codex 或 DeepSeek：" + provider);
        }
        EffectiveSettings selected = provider == ModelProvider.DEEPSEEK
                ? deepSeek.effectiveSettings() : codex.effectiveSettings();
        EffectiveSettings settings = new EffectiveSettings(provider, selected.model(), selected.effort(), selected.version());
        JsonNode frozenSchema = schema.deepCopy();
        ContextBudget budget = requireContextCapacity(provider, systemPrompt, userPrompt, frozenSchema, maxOutputTokens);
        RequestSnapshot snapshot = RequestSnapshot.capture(settings, systemPrompt, userPrompt,
                frozenSchema, schemaName, maxOutputTokens,
                provider == ModelProvider.DEEPSEEK ? "STATELESS" : sessionPolicy.name()).withContextBudget(budget);
        if (provider == ModelProvider.DEEPSEEK) {
            return record(projectId, workflow, provider, systemPrompt, userPrompt, snapshot,
                    () -> deepSeek.request(schemaName, systemPrompt, userPrompt, frozenSchema,
                            maxOutputTokens, settings), processOutput).output();
        }

        CodexAppServerClient.TurnResult result = record(projectId, workflow, provider,
                systemPrompt, userPrompt, snapshot, () -> {
                    CodexAgentSession session = prepareSession(projectId, workflow, systemPrompt, sessionPolicy, settings);
                    CodexAppServerClient.TurnResult turn = codex.runStructuredTurn(
                            session.getThreadId(), projectId, userPrompt, frozenSchema, settings, runs.progressSink());
                    session.recordTurn(turn.turnId());
                    sessions.saveAndFlush(session);
                    return turn;
                }, processOutput);
        return result.output();
    }

    private <T extends AgentRunRecorder.ModelResult> T record(UUID projectId, String workflow, ModelProvider provider,
            String systemPrompt, String userPrompt, RequestSnapshot snapshot, Supplier<T> action, Consumer<String> processOutput) {
        if (processOutput == null) return runs.record(projectId, workflow, provider, systemPrompt, userPrompt, snapshot, action);
        return runs.record(projectId, workflow, provider, systemPrompt, userPrompt, snapshot, action,
                result -> processOutput.accept(result.output()));
    }

    private ContextBudget requireContextCapacity(ModelProvider provider, String systemPrompt,
            String userPrompt, JsonNode schema, int reservedOutputTokens) {
        if (reservedOutputTokens < 0) throw new IllegalArgumentException("模型输出预留不能为负数");
        var capacity = context.require(provider);
        int window = capacity.getContextWindowTokens();
        int safety = capacity.getSafetyMarginTokens();
        long input = MemoryBudgetAllocator.estimateTokens(
                (systemPrompt == null ? "" : systemPrompt) + "\n\n"
                        + (userPrompt == null ? "" : userPrompt) + "\n\n" + schema);
        if (input + reservedOutputTokens + safety > window) {
            throw new IllegalArgumentException("最终模型请求超过配置的上下文容量，请减少输入或输出预留");
        }
        return new ContextBudget(window, safety, input, reservedOutputTokens);
    }

    private CodexAgentSession prepareSession(UUID projectId, String workflow, String systemPrompt,
            CodexSessionPolicy policy, EffectiveSettings settings) {
        CodexAgentSession session = sessions.findByProjectIdAndWorkflowType(projectId, workflow).orElse(null);
        if (policy == CodexSessionPolicy.NEW_THREAD) {
            String threadId = codex.startThread(projectId, systemPrompt, settings);
            if (session == null) session = CodexAgentSession.create(projectId, workflow, threadId);
            else session.replaceThread(threadId);
            return sessions.saveAndFlush(session);
        }
        if (session == null) {
            return sessions.saveAndFlush(CodexAgentSession.create(
                    projectId, workflow, codex.startThread(projectId, systemPrompt, settings)));
        }
        try {
            codex.resumeThread(session.getThreadId(), projectId, systemPrompt, settings);
        } catch (CodexAppServerException exception) {
            if ("AUTHENTICATION".equals(CodexAppServerClient.failureCategory(exception.getMessage()))
                    || !exception.indicatesMissingThread()) throw exception;
            session.replaceThread(codex.startThread(projectId, systemPrompt, settings));
            session = sessions.saveAndFlush(session);
        }
        return session;
    }
}
