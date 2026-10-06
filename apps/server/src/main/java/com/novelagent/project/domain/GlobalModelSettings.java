package com.novelagent.project.domain;

import com.novelagent.planning.application.ModelProvider;

public record GlobalModelSettings(ModelProvider provider, String codexModel, String codexEffort,
        String deepSeekModel, long version) {
    public GlobalModelSettings {
        if (provider == null) throw new IllegalArgumentException("请选择模型供应商");
        new CodexModelChoice(codexModel, codexEffort);
        if (!"deepseek-flash".equals(deepSeekModel) && !"deepseek-v4-pro".equals(deepSeekModel)) {
            throw new IllegalArgumentException("不支持的 DeepSeek 模型");
        }
        if (version < 0) throw new IllegalArgumentException("模型设置版本不合法");
    }
}
