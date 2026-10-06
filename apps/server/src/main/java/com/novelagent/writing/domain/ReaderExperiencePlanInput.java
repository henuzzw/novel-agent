package com.novelagent.writing.domain;

import java.util.UUID;

public record ReaderExperiencePlanInput(UUID requestId, Long expectedVersion, ReaderExperiencePlan.Kind kind,
        String title, String promise, String setup, String payoff, String aftermath, Integer plannedChapter) {
    public void validate(boolean updating) {
        if (requestId == null || kind == null) throw new IllegalArgumentException("请求标识和台账类型不能为空");
        if (updating && (expectedVersion == null || expectedVersion < 0)) throw new IllegalArgumentException("缺少有效的台账版本");
        required(title, 200, "标题");
        required(promise, 4000, "承诺");
        optional(setup); optional(payoff); optional(aftermath);
        if (plannedChapter != null && plannedChapter < 1) throw new IllegalArgumentException("计划章号必须为正数");
    }

    private static void required(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException(field + "为空或过长");
    }

    private static void optional(String value) {
        if (value != null && value.length() > 4000) throw new IllegalArgumentException("计划文本过长");
    }
}
