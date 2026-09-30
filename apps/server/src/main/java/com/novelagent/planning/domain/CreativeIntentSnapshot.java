package com.novelagent.planning.domain;

import com.novelagent.project.domain.CreativeIntent;
import java.util.List;

public record CreativeIntentSnapshot(
        String premise,
        List<String> genres,
        String targetAudience,
        String protagonistBrief,
        String centralConflict,
        List<String> tones,
        Integer targetWords,
        String endingPreference,
        List<String> mustHave,
        List<String> avoid,
        List<String> stylePreferences,
        long sourceVersion) {

    public static CreativeIntentSnapshot from(CreativeIntent intent) {
        return new CreativeIntentSnapshot(
                intent.getPremise(),
                intent.getGenres(),
                intent.getTargetAudience(),
                intent.getProtagonistBrief(),
                intent.getCentralConflict(),
                intent.getTones(),
                intent.getTargetWords(),
                intent.getEndingPreference(),
                intent.getMustHave(),
                intent.getAvoid(),
                intent.getStylePreferences(),
                intent.getRowVersion());
    }
}
