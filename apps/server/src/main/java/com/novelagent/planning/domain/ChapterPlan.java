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
        ChapterPlanStatus status,
        String sceneOutline,
        boolean sceneOutlineNeedsUpdate) {

    public ChapterPlan {
        sceneOutline = sceneOutline == null ? "" : sceneOutline;
        sceneOutlineNeedsUpdate = sceneOutline.isBlank() || sceneOutlineNeedsUpdate;
    }

    public ChapterPlan(int number, String title, String pov, String objective, String coreEvent,
            String reveal, String endingHook, int suggestedMinWords, int suggestedMaxWords, ChapterPlanStatus status) {
        this(number, title, pov, objective, coreEvent, reveal, endingHook,
                suggestedMinWords, suggestedMaxWords, status, "", true);
    }

    public ChapterPlan(int number, String title, String pov, String objective, String coreEvent,
            String reveal, String endingHook, int suggestedMinWords, int suggestedMaxWords) {
        this(number, title, pov, objective, coreEvent, reveal, endingHook,
                suggestedMinWords, suggestedMaxWords, ChapterPlanStatus.PLANNED);
    }

    public ChapterPlan withSceneOutline(String text) {
        return new ChapterPlan(number, title, pov, objective, coreEvent, reveal, endingHook,
                suggestedMinWords, suggestedMaxWords, status, text, false);
    }

    public ChapterPlan withStatus(ChapterPlanStatus value) {
        return new ChapterPlan(number, title, pov, objective, coreEvent, reveal, endingHook,
                suggestedMinWords, suggestedMaxWords, value, sceneOutline, sceneOutlineNeedsUpdate);
    }

    /** Keep stale text visible; changing its basis must not silently certify or regenerate it. */
    public ChapterPlan reviewScenesAgainst(ChapterPlan previous, boolean upstreamChanged) {
        if (previous == null) return this;
        boolean sameText = java.util.Objects.equals(sceneOutline, previous.sceneOutline);
        boolean changed = upstreamChanged || !java.util.Objects.equals(title, previous.title)
                || !java.util.Objects.equals(pov, previous.pov) || !java.util.Objects.equals(objective, previous.objective)
                || !java.util.Objects.equals(coreEvent, previous.coreEvent) || !java.util.Objects.equals(reveal, previous.reveal)
                || !java.util.Objects.equals(endingHook, previous.endingHook) || status != previous.status;
        return new ChapterPlan(number, title, pov, objective, coreEvent, reveal, endingHook,
                suggestedMinWords, suggestedMaxWords, status, sceneOutline,
                sameText && (previous.sceneOutlineNeedsUpdate || changed));
    }
}
