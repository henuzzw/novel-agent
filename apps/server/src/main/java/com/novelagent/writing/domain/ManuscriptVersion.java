package com.novelagent.writing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "manuscript_version")
public class ManuscriptVersion {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "source_contract_version_id") private UUID sourceContractVersionId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "writing_basis", columnDefinition = "jsonb")
    private ManuscriptBasis writingBasis;
    @Column(name = "base_manuscript_version_id") private UUID baseManuscriptVersionId;
    @Column(name = "source_review_version_id") private UUID sourceReviewVersionId;
    @Column(name = "chapter_number", nullable = false) private int chapterNumber;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(name = "schema_version", nullable = false, length = 50) private String schemaVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ManuscriptStatus status;
    @Column(name = "generator_type", nullable = false, length = 50) private String generatorType;
    @Column(name = "author_instruction", columnDefinition = "text") private String authorInstruction;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private ManuscriptContent content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "change_summary", nullable = false, columnDefinition = "jsonb")
    private List<String> changeSummary = new ArrayList<>();
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ManuscriptVersion() {}
    private ManuscriptVersion(UUID id, UUID projectId, UUID contractId, int chapterNumber, int versionNumber,
            String generatorType, String instruction, UUID baseManuscriptVersionId,
            ManuscriptContent content, List<String> changeSummary) {
        this.id = id; this.projectId = projectId; this.sourceContractVersionId = contractId;
        this.baseManuscriptVersionId = baseManuscriptVersionId;
        this.chapterNumber = chapterNumber; this.versionNumber = versionNumber; this.schemaVersion = "manuscript/1";
        this.status = ManuscriptStatus.DRAFT; this.generatorType = required(generatorType, "generatorType");
        this.authorInstruction = instruction; this.content = validate(content);
        this.changeSummary = changeSummary == null ? new ArrayList<>() : new ArrayList<>(changeSummary);
    }
    public static ManuscriptVersion create(UUID id, UUID projectId, UUID contractId, int chapterNumber,
            int versionNumber, String generatorType, String instruction, ManuscriptContent content) {
        return create(id, projectId, contractId, chapterNumber, versionNumber, generatorType, instruction,
                null, content, List.of());
    }
    public static ManuscriptVersion create(UUID id, UUID projectId, UUID contractId, int chapterNumber,
            int versionNumber, String generatorType, String instruction, ManuscriptContent content,
            List<String> changeSummary) {
        return create(id, projectId, contractId, chapterNumber, versionNumber, generatorType, instruction,
                null, content, changeSummary);
    }
    public static ManuscriptVersion create(UUID id, UUID projectId, UUID contractId, int chapterNumber,
            int versionNumber, String generatorType, String instruction, UUID baseManuscriptVersionId,
            ManuscriptContent content, List<String> changeSummary) {
        if (chapterNumber <= 0 || versionNumber <= 0) throw new IllegalArgumentException("章节号和版本号必须为正数");
        return new ManuscriptVersion(id, projectId, contractId, chapterNumber, versionNumber,
                generatorType, instruction, baseManuscriptVersionId, content, changeSummary);
    }
    public void revise(ManuscriptContent value) {
        if (status == ManuscriptStatus.AUTHOR_ACCEPTED) throw new IllegalStateException("作者已确认的正文不能修改，请生成新版本");
        content = validate(value);
    }
    public void accept() { status = ManuscriptStatus.AUTHOR_ACCEPTED; }
    /** New manuscripts persist their outline source; legacy versions retain their historical contract link. */
    public ManuscriptVersion withWritingBasis(ManuscriptBasis basis) {
        if (writingBasis != null) throw new IllegalStateException("写作依据不能覆盖");
        writingBasis = java.util.Objects.requireNonNull(basis);
        return this;
    }
    public ManuscriptVersion inheritWritingBasis(ManuscriptVersion source) {
        if (source.writingBasis != null) withWritingBasis(source.writingBasis);
        return this;
    }
    public void linkReturnedReview(UUID reviewVersionId) {
        if (sourceReviewVersionId != null) throw new IllegalStateException("正文已关联打回审稿");
        sourceReviewVersionId = java.util.Objects.requireNonNull(reviewVersionId);
    }
    private static ManuscriptContent validate(ManuscriptContent value) {
        if (value == null) throw new IllegalArgumentException("正文不能为空");
        required(value.title(), "title"); required(value.body(), "body"); required(value.summary(), "summary");
        return value;
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("正文缺少字段：" + field);
        return value;
    }
    public UUID getId() { return id; } public UUID getProjectId() { return projectId; }
    public UUID getSourceContractVersionId() { return sourceContractVersionId; }
    public ManuscriptBasis getWritingBasis() { return writingBasis; }
    public UUID getBaseManuscriptVersionId() { return baseManuscriptVersionId; }
    public UUID getSourceReviewVersionId() { return sourceReviewVersionId; }
    public int getChapterNumber() { return chapterNumber; } public int getVersionNumber() { return versionNumber; }
    public String getSchemaVersion() { return schemaVersion; } public ManuscriptStatus getStatus() { return status; }
    public String getGeneratorType() { return generatorType; } public String getAuthorInstruction() { return authorInstruction; }
    public ManuscriptContent getContent() { return content; } public long getRowVersion() { return rowVersion; }
    public List<String> getChangeSummary() { return List.copyOf(changeSummary); }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
