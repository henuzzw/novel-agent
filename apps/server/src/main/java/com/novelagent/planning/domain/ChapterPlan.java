package com.novelagent.planning.domain;

public record ChapterPlan(
        int number,
        String title,
        String pov,
        String objective,
        String coreEvent,
        String reveal,
        String endingHook,
        int suggestedMinWords,
        int suggestedMaxWords,
        ChapterPlanStatus status) {

    public ChapterPlan(int number, String title, String pov, String objective, String coreEvent,
            String reveal, String endingHook, int suggestedMinWords, int suggestedMaxWords) {
        this(number, title, pov, objective, coreEvent, reveal, endingHook,
                suggestedMinWords, suggestedMaxWords, ChapterPlanStatus.PLANNED);
    }
}
