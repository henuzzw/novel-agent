package com.novelagent.planning.domain;

import java.util.List;

public record OutlineContent(
        String title,
        String premise,
        String structureSummary,
        String pacingStrategy,
        int suggestedMinWords,
        int suggestedMaxWords,
        List<OutlineArc> arcs,
        List<ReaderExperienceSeed> readerExperiencePlans) {

    public OutlineContent {
        readerExperiencePlans = StoryBibleContent.validatedPlans(readerExperiencePlans);
    }

    public OutlineContent(String title, String premise, String structureSummary, String pacingStrategy,
            int suggestedMinWords, int suggestedMaxWords, List<OutlineArc> arcs) {
        this(title, premise, structureSummary, pacingStrategy, suggestedMinWords, suggestedMaxWords, arcs, List.of());
    }

    public int chapterCount() {
        return arcs == null ? 0 : arcs.stream()
                .filter(arc -> arc != null && arc.chapters() != null)
                .mapToInt(arc -> arc.chapters().size())
                .sum();
    }

    public OutlineContent reviewScenesAgainst(OutlineContent previous, boolean sourceChanged) {
        if (previous == null || arcs == null) return this;
        var oldChapters = previous.arcs().stream().flatMap(arc -> arc.chapters().stream())
                .collect(java.util.stream.Collectors.toMap(ChapterPlan::number, java.util.function.Function.identity()));
        boolean bookChanged = sourceChanged || !java.util.Objects.equals(premise, previous.premise)
                || !java.util.Objects.equals(structureSummary, previous.structureSummary)
                || !java.util.Objects.equals(pacingStrategy, previous.pacingStrategy)
                || !java.util.Objects.equals(readerExperiencePlans, previous.readerExperiencePlans);
        var updated = arcs.stream().map(arc -> {
            var oldArc = previous.arcs().stream().filter(value -> value.ordinal() == arc.ordinal()).findFirst().orElse(null);
            boolean arcChanged = oldArc == null || !java.util.Objects.equals(arc.objective(), oldArc.objective())
                    || !java.util.Objects.equals(arc.mainConflict(), oldArc.mainConflict())
                    || !java.util.Objects.equals(arc.turningPoint(), oldArc.turningPoint())
                    || !java.util.Objects.equals(arc.outcome(), oldArc.outcome());
            return new OutlineArc(arc.ordinal(), arc.title(), arc.objective(), arc.mainConflict(), arc.turningPoint(),
                    arc.outcome(), arc.suggestedMinWords(), arc.suggestedMaxWords(), arc.chapters().stream()
                    .map(chapter -> chapter.reviewScenesAgainst(oldChapters.get(chapter.number()), bookChanged || arcChanged)).toList());
        }).toList();
        return new OutlineContent(title, premise, structureSummary, pacingStrategy, suggestedMinWords,
                suggestedMaxWords, updated, readerExperiencePlans);
    }
}
