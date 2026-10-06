package com.novelagent.planning.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class PlanningBatchAssembler {
    private PlanningBatchAssembler() { }

    public static OutlineContent assemble(String title, StoryBibleContent bible, OutlineWordBudget budget,
            int chapterTo, List<PlanningCheckpoint> checkpoints) {
        var arcs = new ArrayList<OutlineArc>();
        int expected = 1;
        for (var checkpoint : checkpoints) {
            if (checkpoint.status() != PlanningCheckpoint.Status.SUCCEEDED || checkpoint.chapterFrom() != expected) {
                throw new IllegalArgumentException("规划片段必须从第一章连续成功，不能缺块或重叠");
            }
            checkpoint.result().requireRange(checkpoint.chapterFrom(), checkpoint.chapterTo());
            for (var arc : checkpoint.result().arcs()) {
                arcs.add(new OutlineArc(arcs.size() + 1, arc.title(), arc.objective(), arc.mainConflict(),
                        arc.turningPoint(), arc.outcome(), arc.suggestedMinWords(), arc.suggestedMaxWords(),
                        arc.chapters().stream().sorted(Comparator.comparingInt(ChapterPlan::number)).toList()));
            }
            expected = checkpoint.chapterTo() + 1;
        }
        if (expected != chapterTo + 1) throw new IllegalArgumentException("规划片段尚未覆盖整个批次");
        var chapters = arcs.stream().flatMap(arc -> arc.chapters().stream()).toList();
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).number() != i + 1) throw new IllegalArgumentException("卷章排序不连续，须重新检查规划片段");
        }
        long min = chapters.stream().mapToLong(ChapterPlan::suggestedMinWords).sum();
        long max = chapters.stream().mapToLong(ChapterPlan::suggestedMaxWords).sum();
        if (min > budget.acceptableMaxWords() || max < budget.acceptableMinWords()) {
            throw new IllegalArgumentException("各章建议篇幅与作品总字数区间没有交集，请调整规划要求或目标字数");
        }
        return new OutlineContent(title, bible.logline(),
                String.join("；", arcs.stream().map(OutlineArc::objective).toList()), bible.narrativeStyle(),
                (int) Math.max(min, budget.acceptableMinWords()),
                (int) Math.min(max, budget.acceptableMaxWords()), List.copyOf(arcs));
    }
}
