package com.novelagent.planning.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record PlanningCheckpointResult(List<OutlineArc> arcs) {
    public PlanningCheckpointResult {
        if (arcs == null || arcs.isEmpty()) throw new IllegalArgumentException("规划分块至少需要一个卷或幕");
        arcs = arcs.stream().map(arc -> {
            if (arc == null || arc.chapters() == null || arc.chapters().isEmpty()) {
                throw new IllegalArgumentException("规划分块的卷或幕缺少章节");
            }
            return new OutlineArc(arc.ordinal(), arc.title(), arc.objective(), arc.mainConflict(), arc.turningPoint(),
                    arc.outcome(), arc.suggestedMinWords(), arc.suggestedMaxWords(), List.copyOf(arc.chapters()));
        }).toList();
    }

    public void requireRange(int from, int to) {
        Set<Integer> chapters = new HashSet<>();
        for (OutlineArc arc : arcs) {
            if (blank(arc.title()) || blank(arc.objective()) || arc.ordinal() < 1) {
                throw new IllegalArgumentException("规划分块卷或幕的标题、目标或序号不可用");
            }
            for (ChapterPlan chapter : arc.chapters()) {
                if (chapter.number() < from || chapter.number() > to || !chapters.add(chapter.number())) {
                    throw new IllegalArgumentException("规划分块包含越界或重复章节");
                }
                if (blank(chapter.title()) || blank(chapter.pov()) || blank(chapter.objective()) || blank(chapter.coreEvent())
                        || chapter.suggestedMinWords() <= 0 || chapter.suggestedMaxWords() < chapter.suggestedMinWords()) {
                    throw new IllegalArgumentException("规划分块章节内容不完整");
                }
                if (chapter.status() != ChapterPlanStatus.PLANNED) {
                    throw new IllegalArgumentException("新规划分块不能包含已发生章节");
                }
            }
        }
        if (chapters.size() != to - from + 1) throw new IllegalArgumentException("规划分块未覆盖冻结的章节范围");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
