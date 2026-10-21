package com.novelagent.planning.domain;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                .collect(Collectors.toMap(ChapterPlan::number, Function.identity()));
        boolean bookChanged = sourceChanged || !Objects.equals(premise, previous.premise)
                || !Objects.equals(structureSummary, previous.structureSummary)
                || !Objects.equals(pacingStrategy, previous.pacingStrategy)
                || !Objects.equals(readerExperiencePlans, previous.readerExperiencePlans);
        var updated = arcs.stream().map(arc -> {
            var oldArc = previous.arcs().stream().filter(value -> value.ordinal() == arc.ordinal()).findFirst().orElse(null);
            boolean arcChanged = oldArc == null || !Objects.equals(arc.objective(), oldArc.objective())
                    || !Objects.equals(arc.mainConflict(), oldArc.mainConflict())
                    || !Objects.equals(arc.turningPoint(), oldArc.turningPoint())
                    || !Objects.equals(arc.outcome(), oldArc.outcome());
            return arc.withChapters(arc.chapters().stream()
                    .map(chapter -> chapter.reviewContentAgainst(oldChapters.get(chapter.number()), bookChanged || arcChanged)).toList());
        }).toList();
        return new OutlineContent(title, premise, structureSummary, pacingStrategy, suggestedMinWords,
                suggestedMaxWords, updated, readerExperiencePlans);
    }
}
