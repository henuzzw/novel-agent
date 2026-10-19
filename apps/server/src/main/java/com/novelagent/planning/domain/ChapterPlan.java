package com.novelagent.planning.domain;

/** Chapter content is one opaque writing plan; only routing and freshness metadata are structured. */
public record ChapterPlan(int number, String title, String content, ChapterPlanStatus status,
        boolean contentNeedsUpdate) {
    public ChapterPlan {
        if (number < 1 || title == null || title.isBlank() || content == null || content.isBlank() || status == null)
            throw new IllegalArgumentException("章节编号、标题、规划文字块和状态不能为空");
    }

    public ChapterPlan(int number, String title, String content, ChapterPlanStatus status) {
        this(number, title, content, status, false);
    }

    // Read existing JSONB without rewriting published plans or losing any old prose.
    @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DELEGATING)
    public static ChapterPlan read(com.fasterxml.jackson.databind.JsonNode node) {
        ChapterPlanStatus status = ChapterPlanStatus.valueOf(node.path("status").asText("PLANNED"));
        if (node.has("content")) return new ChapterPlan(node.path("number").asInt(), node.path("title").asText(),
                node.path("content").asText(), status, node.path("contentNeedsUpdate").asBoolean(false));
        if (!node.has("coreEvent")) throw new IllegalArgumentException("章节缺少规划文字块content");
        return new ChapterPlan(node.path("number").asInt(), node.path("title").asText(),
                node.path("pov").asText(), node.path("objective").asText(), node.path("coreEvent").asText(),
                node.path("reveal").asText(), node.path("endingHook").asText(),
                node.path("suggestedMinWords").asInt(), node.path("suggestedMaxWords").asInt(), status,
                node.path("sceneOutline").asText(), node.path("sceneOutlineNeedsUpdate").asBoolean(false));
    }

    public ChapterPlan withStatus(ChapterPlanStatus value) {
        return new ChapterPlan(number, title, content, value, contentNeedsUpdate);
    }

    public ChapterPlan reviewContentAgainst(ChapterPlan previous, boolean upstreamChanged) {
        if (previous == null) return new ChapterPlan(number, title, content, status, false);
        boolean reused = java.util.Objects.equals(content, previous.content);
        boolean changed = upstreamChanged || !java.util.Objects.equals(title, previous.title) || status != previous.status;
        return new ChapterPlan(number, title, content, status,
                reused && (previous.contentNeedsUpdate || changed));
    }

    /** Transitional construction for saved fixtures, not an output contract. */
    public ChapterPlan(int number, String title, String pov, String objective, String coreEvent,
            String reveal, String endingHook, int minWords, int maxWords, ChapterPlanStatus status,
            String scenes, boolean needsUpdate) {
        this(number, title, "视角：" + pov + "\n本章目标：" + objective + "\n核心事件：" + coreEvent
                + "\n必要揭示：" + reveal + "\n章末期待：" + endingHook
                + "\n字数要求：" + minWords + "～" + maxWords + "字"
                + (scenes == null || scenes.isBlank() ? "" : "\n场景清单与关键场景展开：\n" + scenes), status, needsUpdate);
    }

    public ChapterPlan(int number, String title, String pov, String objective, String coreEvent,
            String reveal, String endingHook, int minWords, int maxWords, ChapterPlanStatus status) {
        this(number, title, pov, objective, coreEvent, reveal, endingHook, minWords, maxWords, status, "", false);
    }

    public ChapterPlan(int number, String title, String pov, String objective, String coreEvent,
            String reveal, String endingHook, int minWords, int maxWords) {
        this(number, title, pov, objective, coreEvent, reveal, endingHook, minWords, maxWords, ChapterPlanStatus.PLANNED);
    }
}
