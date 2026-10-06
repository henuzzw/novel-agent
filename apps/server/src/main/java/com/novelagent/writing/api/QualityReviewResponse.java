package com.novelagent.writing.api;

import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityReviewVersion;
import java.time.Instant;
import java.util.UUID;

public record QualityReviewResponse(UUID id, UUID projectId, int chapterNumber, int versionNumber,
        UUID sourceManuscriptId, long sourceManuscriptRowVersion, String generatorType, boolean current,
        QualityReviewContent content, Instant createdAt) {
    public static QualityReviewResponse from(QualityReviewVersion report, boolean current) {
        return new QualityReviewResponse(report.getId(), report.getProjectId(), report.getChapterNumber(), report.getVersionNumber(),
                report.getSourceManuscriptId(), report.getSourceManuscriptRowVersion(), report.getGeneratorType(), current,
                report.getContent(), report.getCreatedAt());
    }
}
