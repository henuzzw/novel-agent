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
    private final com.novelagent.modelaccess.application.ChatGptDirectGateway direct;
    private final com.novelagent.modelaccess.application.ChatGptTransportService transport;

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

    public StructuredModelGateway(
            CodexAppServerClient codex,
            CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek,
            AgentRunRecorder runs,
            ModelContextProperties context,
            AgentPromptService prompts) {
        this(codex, sessions, deepSeek, runs, context, prompts, null, null);
    }

    @Autowired
    public StructuredModelGateway(CodexAppServerClient codex, CodexAgentSessionRepository sessions,
            DeepSeekStructuredOutputClient deepSeek, AgentRunRecorder runs, ModelContextProperties context,
            AgentPromptService prompts, com.novelagent.modelaccess.application.ChatGptDirectGateway direct,
            com.novelagent.modelaccess.application.ChatGptTransportService transport) {
        this.codex = codex;
        this.codexSessions = new CodexSessionManager(codex, sessions);
        this.deepSeek = deepSeek;
        this.runs = runs;
        this.budget = new StructuredRequestBudget(context);
        this.prompts = prompts;
        this.direct = direct;
        this.transport = transport;
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
        var protocol = new PlainTextOutputProtocol(schema);
        String stageInstructions = PlainTextOutputProtocol.withoutLegacyProtocol(resolved.systemPrompt()) + protocol.instructions();
        boolean sharedPlanning = provider == ModelProvider.LOCAL_CODEX && PlanningConversationPolicy.shares(workflow);
        String effectiveUser = "【本轮服务端执行规范】\n" + stageInstructions
                + "\n【本轮创作资料】\n" + userPrompt;
        String effectiveSystem = PlainTextOutputProtocol.withoutLegacyProtocol(resolved.sessionSystemPrompt())
                + "\n\n" + AgentPromptService.protectedRules(workflow);
        final String instructions = effectiveSystem;
        CodexSessionPolicy effectivePolicy = sharedPlanning ? CodexSessionPolicy.REUSE_THREAD : sessionPolicy;
        boolean useDirect = provider == ModelProvider.LOCAL_CODEX && transport != null && transport.direct();
        EffectiveSettings selected = provider == ModelProvider.DEEPSEEK
                ? deepSeek.effectiveSettings() : useDirect ? direct.effectiveSettings() : codex.effectiveSettings();
        EffectiveSettings settings = new EffectiveSettings(provider, selected.model(), selected.effort(), selected.version());
        JsonNode frozenSchema = com.fasterxml.jackson.databind.node.NullNode.getInstance();
        var contextBudget = budget.requireCapacity(provider, instructions, effectiveUser, frozenSchema, maxOutputTokens);
        RequestSnapshot snapshot = RequestSnapshot.capture(settings, instructions, effectiveUser,
                frozenSchema, "PLAIN_TEXT", maxOutputTokens,
                provider == ModelProvider.DEEPSEEK ? "STATELESS" : effectivePolicy.name()).withContextBudget(contextBudget);
        var decoded = new java.util.concurrent.atomic.AtomicReference<String>();
        Consumer<String> decode = raw -> {
            String output = protocol.decode(raw);
            if (processOutput != null) processOutput.accept(output);
            decoded.set(output);
        };
        if (provider == ModelProvider.DEEPSEEK) {
            record(projectId, workflow, provider, instructions, effectiveUser, snapshot,
                    () -> deepSeek.request(schemaName, instructions, effectiveUser, frozenSchema,
                            maxOutputTokens, settings), decode);
            return decoded.get();
        }

        if (useDirect) {
            try (var request = direct.prepare(projectId, sharedPlanning ? PlanningConversationPolicy.KEY : workflow,
                    instructions, effectiveUser, effectivePolicy,
                    sharedPlanning && resolved.revision() == null ? PlanningConversationPolicy.REVISION : resolved.revision(), maxOutputTokens)) {
                // Record exactly the HTTP input array, including history, not just the latest user message.
                var directSnapshot = RequestSnapshot.capture(settings, instructions, request.wireInput(), frozenSchema,
                        "PLAIN_TEXT", maxOutputTokens, "SIWC_HTTP_" + effectivePolicy.name()).withContextBudget(request.budget());
                runs.record(projectId, workflow, provider, instructions, request.wireInput(), directSnapshot,
                        () -> request.run(settings, runs.progressSink()), result -> {
                            decode.accept(result.output());
                            request.accept(result);
                        });
                return decoded.get();
            }
        }

        record(projectId, workflow, provider,
                instructions, effectiveUser, snapshot,
                () -> codexSessions.run(projectId, sharedPlanning ? PlanningConversationPolicy.KEY : workflow,
                        instructions, effectiveUser, frozenSchema, effectivePolicy, settings,
                        sharedPlanning && resolved.revision() == null ? PlanningConversationPolicy.REVISION : resolved.revision(),
                        runs.progressSink()), decode);
        return decoded.get();
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
