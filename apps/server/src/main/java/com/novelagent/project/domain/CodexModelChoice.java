package com.novelagent.project.domain;

import java.util.Set;

public record CodexModelChoice(String model, String effort) {
    public CodexModelChoice {
        if (model == null || !model.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,99}")) {
            throw new IllegalArgumentException("不支持的 Codex 模型");
        }
        if (!Set.of("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                .contains(effort == null ? "" : effort)) {
            throw new IllegalArgumentException("不支持的 Codex 推理档位");
        }
    }

}
