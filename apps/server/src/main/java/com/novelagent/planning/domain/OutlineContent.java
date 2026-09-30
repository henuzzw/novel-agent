package com.novelagent.planning.domain;

import java.util.List;

public record OutlineContent(
        String title,
        String premise,
        String structureSummary,
        String pacingStrategy,
        int suggestedMinWords,
        int suggestedMaxWords,
        List<OutlineArc> arcs) {

    public int chapterCount() {
        return arcs == null ? 0 : arcs.stream()
                .filter(arc -> arc != null && arc.chapters() != null)
                .mapToInt(arc -> arc.chapters().size())
                .sum();
    }
}
