package com.novelagent.prompt.domain;

import java.time.Instant;

/** 某阶段配对配置：systemPrompt 是用户阶段指令，sessionSystemPrompt 是真实系统角色；null 沿用默认。 */
public record PromptConfiguration(String key, String systemPrompt, String guidance, long version, Instant updatedAt,
        String sessionSystemPrompt) {
    public PromptConfiguration(String key, String systemPrompt, String guidance, long version, Instant updatedAt) {
        this(key, systemPrompt, guidance, version, updatedAt, null);
    }

    public boolean customized() { return systemPrompt != null || sessionSystemPrompt != null || !guidance.isEmpty(); }
}
