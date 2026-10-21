package com.novelagent.planning.domain;

import java.util.List;

public record OutlineArc(
        int ordinal,
        String title,
        String objective,
        String mainConflict,
        String turningPoint,
        String outcome,
        int suggestedMinWords,
        int suggestedMaxWords,
        List<ChapterPlan> chapters) {
    /** 返回仅替换章节列表的副本，其余卷或幕规划字段保持不变。 */
    public OutlineArc withChapters(List<ChapterPlan> chapters) {
        return new OutlineArc(ordinal, title, objective, mainConflict, turningPoint, outcome,
                suggestedMinWords, suggestedMaxWords, chapters);
    }
}
