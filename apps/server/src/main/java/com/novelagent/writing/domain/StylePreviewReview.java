package com.novelagent.writing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "style_preview_review")
public class StylePreviewReview {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "source_hash", nullable = false, length = 64) private String sourceHash;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private StylePreviewSource source;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private QualityReviewContent content;
    @Column(name = "revision_attempted", nullable = false) private boolean revisionAttempted;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected StylePreviewReview() { }

    public static StylePreviewReview create(UUID projectId, String hash, StylePreviewSource source,
            QualityReviewContent content) {
        if (projectId == null || hash == null || !hash.matches("[0-9a-f]{64}") || source == null || content == null) {
            throw new IllegalArgumentException("试写检查来源不完整");
        }
        content.requireEvidenceIn(source.content().body());
        StylePreviewReview result = new StylePreviewReview();
        result.id = UUID.randomUUID();
        result.projectId = projectId;
        result.sourceHash = hash;
        result.source = source;
        result.content = content;
        return result;
    }

    public void beginRevision() {
        if (revisionAttempted) throw new IllegalStateException("本报告已尝试修订，请重新检查后再选择建议");
        revisionAttempted = true;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getSourceHash() { return sourceHash; }
    public StylePreviewSource getSource() { return source; }
    public QualityReviewContent getContent() { return content; }
    public boolean isRevisionAttempted() { return revisionAttempted; }
}
