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
@Table(name = "quality_review_version")
public class QualityReviewVersion {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "chapter_number", nullable = false) private int chapterNumber;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(name = "source_manuscript_id", nullable = false) private UUID sourceManuscriptId;
    @Column(name = "source_manuscript_row_version", nullable = false) private long sourceManuscriptRowVersion;
    @Column(name = "source_outline_id", nullable = false) private UUID sourceOutlineId;
    @Column(name = "source_text_hash", nullable = false, length = 64) private String sourceTextHash;
    @Column(name = "generator_type", nullable = false, length = 32) private String generatorType;
    @Column(name = "author_instruction", columnDefinition = "text") private String authorInstruction;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private QualityReviewContent content;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected QualityReviewVersion() { }

    public static QualityReviewVersion create(UUID projectId, int chapter, int version, UUID sourceManuscriptId,
            long sourceRowVersion, UUID sourceOutlineId, String sourceTextHash, String generatorType,
            String instruction, QualityReviewContent content) {
        if (chapter < 1 || version < 1 || sourceTextHash == null || !sourceTextHash.matches("[0-9a-f]{64}")
                || generatorType == null || content == null) throw new IllegalArgumentException("质量报告版本参数不合法");
        QualityReviewVersion report = new QualityReviewVersion();
        report.id = UUID.randomUUID();
        report.projectId = projectId;
        report.chapterNumber = chapter;
        report.versionNumber = version;
        report.sourceManuscriptId = sourceManuscriptId;
        report.sourceManuscriptRowVersion = sourceRowVersion;
        report.sourceOutlineId = sourceOutlineId;
        report.sourceTextHash = sourceTextHash;
        report.generatorType = generatorType;
        report.authorInstruction = instruction;
        report.content = content;
        return report;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public int getChapterNumber() { return chapterNumber; }
    public int getVersionNumber() { return versionNumber; }
    public UUID getSourceManuscriptId() { return sourceManuscriptId; }
    public long getSourceManuscriptRowVersion() { return sourceManuscriptRowVersion; }
    public UUID getSourceOutlineId() { return sourceOutlineId; }
    public String getSourceTextHash() { return sourceTextHash; }
    public String getGeneratorType() { return generatorType; }
    public String getAuthorInstruction() { return authorInstruction; }
    public QualityReviewContent getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
