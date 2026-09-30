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
}
