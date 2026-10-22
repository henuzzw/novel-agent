package com.novelagent.modelaccess.application;

import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.ModelOutputAvailability;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * 自由文本生成入口，供雪花、书名等用例复用单字段传输协议。
 * 保留原有 snowflake_ 结构名及错误语义，避免迁移模块改变运行记录；文本仍由调用方作业务校验。
 */
@Component
public class FreeTextModelRequest {
    private final StructuredModelGateway models;
    private final ObjectMapper mapper;

    public FreeTextModelRequest(StructuredModelGateway models, ObjectMapper mapper) {
        this.models = models;
        this.mapper = mapper;
    }

    public String request(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String prompt,
            String stage,
            int maxTokens) {
        return request(projectId, workflow, provider, prompt, stage, maxTokens, null);
    }

    public String request(
            UUID projectId,
            String workflow,
            ModelProvider provider,
            String prompt,
            String stage,
            int maxTokens,
            String frozenBoundaries) {
        var schema =
                mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("text");
        schema.putObject("properties").putObject("text").put("type", "string");
        var text = new AtomicReference<String>();
        models.requestWithBoundaries(
                projectId,
                workflow,
                provider,
                AgentPromptDefaults.system(workflow),
                prompt,
                schema,
                "snowflake_" + stage.toLowerCase(Locale.ROOT),
                maxTokens,
                CodexSessionPolicy.REUSE_THREAD,
                raw -> text.set(readText(raw)),
                frozenBoundaries);
        return text.get();
    }

    private String readText(String raw) {
        try {
            var root = mapper.readTree(raw);
            if (root == null
                    || !root.path("text").isTextual()
                    || root.path("text").asText().isBlank()) {
                throw new IllegalArgumentException("雪花规划未返回非空文本");
            }
            String text = root.path("text").asText().trim();
            ModelOutputAvailability.requireAvailable(text);
            return text;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("雪花规划响应无法读取", exception);
        }
    }
}
