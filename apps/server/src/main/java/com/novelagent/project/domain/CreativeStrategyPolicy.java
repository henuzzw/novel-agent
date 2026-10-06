package com.novelagent.project.domain;

import java.util.Map;

public record CreativeStrategyPolicy(CreativeStrategy strategy, int policyVersion) {
    public static final String SETTING_KEY = "creativeStrategy";
    public static final int CURRENT_VERSION = 1;

    public CreativeStrategyPolicy {
        if (strategy == null || policyVersion != CURRENT_VERSION) {
            throw new IllegalArgumentException("创作策略或策略版本不可用");
        }
    }

    public static CreativeStrategyPolicy of(CreativeStrategy strategy) {
        return new CreativeStrategyPolicy(strategy == null ? CreativeStrategy.STANDARD : strategy, CURRENT_VERSION);
    }

    public static CreativeStrategyPolicy from(NovelProject project) {
        Object stored = project.getSetting(SETTING_KEY);
        if (stored == null) {
            return of(CreativeStrategy.STANDARD);
        }
        if (!(stored instanceof Map<?, ?> value)
                || !(value.get("strategy") instanceof String strategy)
                || !(value.get("policyVersion") instanceof Number version)
                || version.doubleValue() != CURRENT_VERSION) {
            throw new IllegalArgumentException("已保存的创作策略不可用，请重新保存项目创作策略");
        }
        return new CreativeStrategyPolicy(CreativeStrategy.valueOf(strategy), CURRENT_VERSION);
    }

    public void applyTo(NovelProject project) {
        project.setSetting(SETTING_KEY, Map.of("strategy", strategy.name(), "policyVersion", policyVersion));
    }
}
