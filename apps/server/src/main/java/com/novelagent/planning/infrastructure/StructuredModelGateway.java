package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.RequestSnapshot;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.prompt.application.AgentPromptService;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class StructuredModelGateway {
    private final CodexAppServerClient codex;
    private final CodexSessionManager codexSessions;
    private final DeepSeekStructuredOutputClient deepSeek;
    private final AgentRunRecorder runs;
    private final StructuredRequestBudget budget;
    private final AgentPromptService prompts;

    public StructuredModelGateway(
            CodexAppServerClient codex,
            CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek,
            AgentRunRecorder runs) {
        this(codex, sessions, deepSeek, runs, new ModelContextProperties());
    }

    public StructuredModelGateway(
            CodexAppServerClient codex,
            CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek,
            AgentRunRecorder runs,
            ModelContextProperties context) {
        this(codex, sessions, deepSeek, runs, context, null);
    }

    @Autowired
    public StructuredModelGateway(
            CodexAppServerClient codex,
            CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek,
            AgentRunRecorder runs,
            ModelContextProperties context,
            AgentPromptService prompts) {
        this.codex = codex;
        this.codexSessions = new CodexSessionManager(codex, sessions);
        this.deepSeek = deepSeek;
        this.runs = runs;
        this.budget = new StructuredRequestBudget(context);
        this.prompts = prompts;
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
        // Freeze the saved template before budgeting, recording and dispatching either provider.
        var resolved = prompts == null ? new AgentPromptService.Resolved(systemPrompt, null)
                : prompts.resolve(workflow, systemPrompt);
        String effectiveSystem = resolved.systemPrompt();
        EffectiveSettings selected = provider == ModelProvider.DEEPSEEK
                ? deepSeek.effectiveSettings() : codex.effectiveSettings();
        EffectiveSettings settings = new EffectiveSettings(provider, selected.model(), selected.effort(), selected.version());
        JsonNode frozenSchema = schema.deepCopy();
        var contextBudget = budget.requireCapacity(provider, effectiveSystem, userPrompt, frozenSchema, maxOutputTokens);
        RequestSnapshot snapshot = RequestSnapshot.capture(settings, effectiveSystem, userPrompt,
                frozenSchema, schemaName, maxOutputTokens,
                provider == ModelProvider.DEEPSEEK ? "STATELESS" : sessionPolicy.name()).withContextBudget(contextBudget);
        if (provider == ModelProvider.DEEPSEEK) {
            return record(projectId, workflow, provider, effectiveSystem, userPrompt, snapshot,
                    () -> deepSeek.request(schemaName, effectiveSystem, userPrompt, frozenSchema,
                            maxOutputTokens, settings), processOutput).output();
        }

        CodexAppServerClient.TurnResult result = record(projectId, workflow, provider,
                effectiveSystem, userPrompt, snapshot,
                () -> codexSessions.run(projectId, workflow, effectiveSystem, userPrompt, frozenSchema,
                        sessionPolicy, settings, resolved.revision(), runs.progressSink()), processOutput);
        return result.output();
    }

    private <T extends AgentRunRecorder.ModelResult> T record(UUID projectId, String workflow, ModelProvider provider,
            String systemPrompt, String userPrompt, RequestSnapshot snapshot, Supplier<T> action, Consumer<String> processOutput) {
        if (processOutput == null) {
            return runs.record(projectId, workflow, provider, systemPrompt, userPrompt, snapshot, action);
        }
        return runs.record(projectId, workflow, provider, systemPrompt, userPrompt, snapshot, action,
                result -> processOutput.accept(result.output()));
    }

}
