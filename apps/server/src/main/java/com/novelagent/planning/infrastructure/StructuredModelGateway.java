package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.planning.application.ModelProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Existing business entry point; selects one adapter, without implicit transport fallback. */
@Component
public class StructuredModelGateway {
    private final Map<AbstractModelExecutor.Entry, AbstractModelExecutor> executors;
    private final ModelPromptAssembler prompts;
    private final ChatGptTransportService transport;

    public StructuredModelGateway(List<AbstractModelExecutor> executors, ModelPromptAssembler prompts,
            ChatGptTransportService transport) {
        var registered = new EnumMap<AbstractModelExecutor.Entry, AbstractModelExecutor>(AbstractModelExecutor.Entry.class);
        for (var executor : executors) {
            if (registered.putIfAbsent(executor.entry(), executor) != null)
                throw new IllegalStateException("模型入口重复注册：" + executor.entry());
        }
        this.executors = Map.copyOf(registered);
        this.prompts = prompts;
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
        return requestWithBoundaries(projectId, workflow, provider, systemPrompt, userPrompt, schema, schemaName,
                maxOutputTokens, sessionPolicy, processOutput, null);
    }

    public String requestWithBoundaries(UUID projectId, String workflow, ModelProvider provider,
            String systemPrompt, String userPrompt, JsonNode schema, String schemaName,
            int maxOutputTokens, CodexSessionPolicy sessionPolicy, Consumer<String> processOutput, String frozenBoundaries) {
        var entry = select(provider);
        var executor = executors.get(entry);
        if (executor == null) throw new IllegalStateException("模型入口未初始化：" + entry);
        var request = prompts.assemble(projectId, workflow, provider, systemPrompt, userPrompt, schema, schemaName,
                maxOutputTokens, sessionPolicy, frozenBoundaries);
        return executor.execute(request, processOutput);
    }

    private AbstractModelExecutor.Entry select(ModelProvider provider) {
        if (provider == ModelProvider.DEEPSEEK) return AbstractModelExecutor.Entry.DEEPSEEK;
        if (provider != ModelProvider.LOCAL_CODEX)
            throw new IllegalArgumentException("当前模型调用只支持服务端 Codex 或 DeepSeek：" + provider);
        // Read the transport once, so concurrent settings changes cannot mix two transports.
        var selected = transport == null ? ChatGptTransportService.Transport.APP_SERVER : transport.get().transport();
        return switch (selected) {
            case APP_SERVER -> AbstractModelExecutor.Entry.LEGACY_CODEX;
            case SIWC_CODEX -> AbstractModelExecutor.Entry.PROJECT_CODEX;
            case SIWC_HTTP -> AbstractModelExecutor.Entry.CHATGPT_HTTP;
        };
    }
}
