package com.novelagent.modelaccess.infrastructure;

import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.modelaccess.domain.ModelProvider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 模型调用端口的路由实现；每次请求只选择一个执行器，不隐式切换传输或供应商。 */
@Component
public class RoutedStructuredModelGateway implements StructuredModelGateway {
    private final Map<AbstractModelExecutor.Entry, AbstractModelExecutor> executors;
    private final ModelPromptAssembler prompts;
    private final ChatGptTransportService transport;

    public RoutedStructuredModelGateway(
            List<AbstractModelExecutor> executors,
            ModelPromptAssembler prompts,
            ChatGptTransportService transport) {
        var registered =
                new EnumMap<AbstractModelExecutor.Entry, AbstractModelExecutor>(
                        AbstractModelExecutor.Entry.class);
        for (var executor : executors) {
            if (registered.putIfAbsent(executor.entry(), executor) != null)
                throw new IllegalStateException("模型入口重复注册：" + executor.entry());
        }
        this.executors = Map.copyOf(registered);
        this.prompts = prompts;
        this.transport = transport;
    }

    @Override
    public String requestWithBoundaries(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String systemPrompt,
            String userPrompt,
            JsonNode schema,
            String schemaName,
            int maxOutputTokens,
            CodexSessionPolicy sessionPolicy,
            Consumer<String> processOutput,
            String frozenBoundaries) {
        return execute(
                projectId,
                workflow,
                provider,
                systemPrompt,
                userPrompt,
                schema,
                schemaName,
                maxOutputTokens,
                sessionPolicy,
                processOutput,
                frozenBoundaries,
                false);
    }

    /** B/C machine reports opt in explicitly; ordinary writing and planning remain prose. */
    @Override
    public String requestJsonWithBoundaries(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String systemPrompt,
            String userPrompt,
            JsonNode schema,
            String schemaName,
            int maxOutputTokens,
            CodexSessionPolicy sessionPolicy,
            Consumer<String> processOutput,
            String frozenBoundaries) {
        return execute(
                projectId,
                workflow,
                provider,
                systemPrompt,
                userPrompt,
                schema,
                schemaName,
                maxOutputTokens,
                sessionPolicy,
                processOutput,
                frozenBoundaries,
                true);
    }

    private String execute(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String systemPrompt,
            String userPrompt,
            JsonNode schema,
            String schemaName,
            int maxOutputTokens,
            CodexSessionPolicy sessionPolicy,
            Consumer<String> processOutput,
            String frozenBoundaries,
            boolean nativeJson) {
        var entry = select(provider);
        var executor = executors.get(entry);
        if (executor == null) throw new IllegalStateException("模型入口未初始化：" + entry);
        var request =
                prompts.assemble(
                        projectId,
                        workflow,
                        provider,
                        systemPrompt,
                        userPrompt,
                        schema,
                        schemaName,
                        maxOutputTokens,
                        sessionPolicy,
                        frozenBoundaries,
                        nativeJson);
        return executor.execute(request, processOutput);
    }

    private AbstractModelExecutor.Entry select(ModelProvider provider) {
        if (provider == ModelProvider.DEEPSEEK) return AbstractModelExecutor.Entry.DEEPSEEK;
        if (provider != ModelProvider.LOCAL_CODEX)
            throw new IllegalArgumentException("当前模型调用只支持服务端 Codex 或 DeepSeek：" + provider);
        // Read the transport once, so concurrent settings changes cannot mix two transports.
        var selected =
                transport == null
                        ? ChatGptTransportService.Transport.APP_SERVER
                        : transport.get().transport();
        return switch (selected) {
            case APP_SERVER -> AbstractModelExecutor.Entry.LEGACY_CODEX;
            case SIWC_CODEX -> AbstractModelExecutor.Entry.PROJECT_CODEX;
            case SIWC_HTTP -> AbstractModelExecutor.Entry.CHATGPT_HTTP;
        };
    }
}
