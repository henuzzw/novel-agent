package com.novelagent.project.api;

import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.domain.ProjectStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        EntryMode entryMode,
        ProjectStatus status,
        long currentCanonVersion,
        long version,
        Instant createdAt,
        Instant updatedAt,
        CreativeIntentResponse creativeIntent,
        CreativeStrategy creativeStrategy) {

    public static ProjectResponse from(NovelProject project, CreativeIntent intent) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getEntryMode(),
                project.getStatus(),
                project.getCurrentCanonVersion(),
                project.getRowVersion(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                CreativeIntentResponse.from(intent),
                CreativeStrategyPolicy.from(project).strategy());
    }

    public record CreativeIntentResponse(
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
            long version) {

        public static CreativeIntentResponse from(CreativeIntent intent) {
            if (intent == null) {
                return null;
            }
            return new CreativeIntentResponse(
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
}
