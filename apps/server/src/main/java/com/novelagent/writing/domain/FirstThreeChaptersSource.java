package com.novelagent.writing.domain;

import java.util.List;
import java.util.UUID;

/** Immutable input shared by the reader and the whole-opening review. */
public record FirstThreeChaptersSource(UUID projectId, UUID outlineId, long outlineRowVersion,
        UUID bibleId, long bibleRowVersion, long canonVersion, String strategy,
        String outlineContext, String bibleContext, String styleContext, String profileContext,
        List<Chapter> chapters, List<String> unavailableReasons, String fingerprint) {
    public boolean available() { return unavailableReasons.isEmpty() && chapters.size() == 3
            && chapters.stream().allMatch(c -> c.body() != null && !c.body().isBlank()); }
    public record Version(UUID id, int versionNumber, long rowVersion, String status) { }
    public record Chapter(int chapterNumber, UUID manuscriptId, int versionNumber, long rowVersion,
            String status, String title, String body, UUID contractId, int contractVersionNumber,
            long contractRowVersion, String contractStatus, ChapterContractContent contract,
            List<Version> versions, QualityReviewContent qualityReview, boolean qualityReviewCurrent) { }
}
