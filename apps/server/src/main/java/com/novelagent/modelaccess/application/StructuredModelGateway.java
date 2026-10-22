package com.novelagent.modelaccess.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.novelagent.modelaccess.domain.ModelProvider;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 业务模块的模型调用端口。调用方提供阶段、提示词和输出约束，模型接入模块负责路由、预算、会话及运行记录。
 * 输出校验与保存回调成功后才确认会话资料已交付；异常沿原调用链传播，不切换供应商重试。
 */
public interface StructuredModelGateway {
    default String request(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String systemPrompt,
            String userPrompt,
            JsonNode schema,
            String schemaName,
            int maxOutputTokens,
            CodexSessionPolicy sessionPolicy) {
        return request(
                projectId,
                workflow,
                provider,
                systemPrompt,
                userPrompt,
                schema,
                schemaName,
                maxOutputTokens,
                sessionPolicy,
                null);
    }

    default String request(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String systemPrompt,
            String userPrompt,
            JsonNode schema,
            String schemaName,
            int maxOutputTokens,
            CodexSessionPolicy sessionPolicy,
            Consumer<String> processOutput) {
        return requestWithBoundaries(
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
                null);
    }

    /** 使用纯文本输出协议；传入冻结边界时不重新读取可变项目边界。 */
    String requestWithBoundaries(
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
            String frozenBoundaries);

    /** 显式使用原生 JSON Schema 协议，供自动编辑 B/C 等机器报告使用。 */
    String requestJsonWithBoundaries(
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
            String frozenBoundaries);
}
