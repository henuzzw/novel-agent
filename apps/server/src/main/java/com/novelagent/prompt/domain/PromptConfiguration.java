package com.novelagent.prompt.domain;

import java.time.Instant;

/** 当前用户某一阶段的配置；null 系统文本表示沿用代码默认值，不复制项目输入。 */
public record PromptConfiguration(String key, String systemPrompt, String guidance, long version, Instant updatedAt) {
    public boolean customized() { return systemPrompt != null || !guidance.isEmpty(); }
}
