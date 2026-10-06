package com.novelagent.project.domain;

import java.util.Map;
import java.util.Set;

public record CodexModelChoice(String model, String effort) {
    public static final String SETTING_KEY = "codexModel";

    public CodexModelChoice {
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("不支持的 Codex 模型");
        }
        if (!Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                .contains(effort == null ? "" : effort)) {
            throw new IllegalArgumentException("不支持的 Codex 推理档位");
        }
    }

    public static CodexModelChoice from(NovelProject project, String defaultModel, String defaultEffort) {
        Object value = project.getSetting(SETTING_KEY);
        if (value instanceof Map<?, ?> saved) {
            return new CodexModelChoice((String) saved.get("model"), (String) saved.get("effort"));
        }
        return new CodexModelChoice(defaultModel, defaultEffort);
    }

    public Map<String, Object> asSetting() {
        return Map.of("model", model, "effort", effort);
    }
}
