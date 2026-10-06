package com.novelagent.writing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "first_three_chapters_report")
public class FirstThreeChaptersReport {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "author_id", nullable = false) private UUID authorId;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(nullable = false, length = 32) private String provider;
    @Column(name = "review_mode", nullable = false, length = 32) private String reviewMode;
    @Column(columnDefinition = "text") private String instruction;
    @Column(name = "schema_version", nullable = false, length = 50) private String schemaVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private FirstThreeChaptersSource source;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private FirstThreeChaptersContent content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private FirstThreeChaptersBudget budget;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    protected FirstThreeChaptersReport() { }
    public static FirstThreeChaptersReport create(UUID authorId, int version, String provider, String instruction,
            FirstThreeChaptersSource source, FirstThreeChaptersContent content, FirstThreeChaptersBudget budget) {
        if (!source.available()) throw new IllegalArgumentException("必须有完整三章正文与依据");
        content.validate(source, "LOCAL_TEMPLATE".equals(provider));
        var report = new FirstThreeChaptersReport();
        report.id = UUID.randomUUID(); report.projectId = source.projectId(); report.authorId = authorId;
        report.versionNumber = version; report.fingerprint = source.fingerprint(); report.provider = provider;
        report.reviewMode = "LOCAL_TEMPLATE".equals(provider) ? "RULES_ONLY" : "MODEL";
        report.instruction = instruction; report.schemaVersion = "opening-review/1";
        report.source = source; report.content = content; report.budget = budget;
        return report;
    }
    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getAuthorId() { return authorId; }
    public int getVersionNumber() { return versionNumber; }
    public String getFingerprint() { return fingerprint; }
    public String getProvider() { return provider; }
    public String getReviewMode() { return reviewMode; }
    public FirstThreeChaptersSource getSource() { return source; }
    public FirstThreeChaptersContent getContent() { return content; }
    public FirstThreeChaptersBudget getBudget() { return budget; }
    public Instant getCreatedAt() { return createdAt; }
}
