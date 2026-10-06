package com.novelagent.planning.domain;

public record ReaderExperienceSeed(String key, String kind, String title, String promise,
        String setup, String payoff, String aftermath, Integer plannedChapter) {
    public ReaderExperienceSeed {
        key = required(key, 80);
        if (!key.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("台账规划标识无效");
        if (!"PROMISE".equals(kind) && !"FORESHADOW".equals(kind)) throw new IllegalArgumentException("台账规划类型无效");
        title = required(title, 200);
        promise = required(promise, 4000);
        setup = text(setup); payoff = text(payoff); aftermath = text(aftermath);
        if (plannedChapter != null && plannedChapter < 1) throw new IllegalArgumentException("计划章号必须为正数");
    }

    private static String required(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("台账规划字段为空或过长");
        return value.trim();
    }
    private static String text(String value) {
        if (value != null && value.length() > 4000) throw new IllegalArgumentException("台账规划文本过长");
        return value == null ? "" : value.trim();
    }
}
