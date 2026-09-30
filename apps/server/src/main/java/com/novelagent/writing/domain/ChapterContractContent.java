package com.novelagent.writing.domain;

import java.util.List;

public record ChapterContractContent(
        String chapterTitle, String pov, String objective, String storyTime,
        List<String> locations, List<String> requiredBeats, List<String> requiredReveals,
        List<String> forbiddenFacts, String expectedExitState,
        List<String> foreshadowActions, String hook,
        int suggestedMinWords, int suggestedMaxWords) {
}
