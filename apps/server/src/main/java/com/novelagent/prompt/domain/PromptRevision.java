package com.novelagent.prompt.domain;

import java.time.Instant;

/** 保存不可变的作者编辑历史，恢复默认也作为一个新版本记录，而非删除历史。 */
public record PromptRevision(long version, String systemPrompt, String guidance, String operation, Instant createdAt,
        String sessionSystemPrompt) {
    public PromptRevision(long version, String systemPrompt, String guidance, String operation, Instant createdAt) {
        this(version, systemPrompt, guidance, operation, createdAt, null);
    }
}
