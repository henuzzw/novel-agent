package com.novelagent.writing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chapter_contract_review_version")
public class ChapterContractReviewVersion {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "chapter_number", nullable = false) private int chapterNumber;
    @Column(name = "source_contract_version_id", nullable = false) private UUID sourceContractVersionId;
    @Column(name = "source_contract_row_version", nullable = false) private long sourceContractRowVersion;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ReviewStatus status;
    @Column(name = "generator_type", nullable = false, length = 50) private String generatorType;
    @Column(name = "author_instruction", columnDefinition = "text") private String authorInstruction;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private ChapterContractReviewContent content;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ChapterContractReviewVersion() {}

    private ChapterContractReviewVersion(UUID id, UUID projectId, int chapterNumber, UUID contractId,
            long contractRowVersion, int versionNumber, String generatorType, String instruction,
            ChapterContractReviewContent content) {
        this.id = id; this.projectId = projectId; this.chapterNumber = chapterNumber;
        this.sourceContractVersionId = contractId; this.sourceContractRowVersion = contractRowVersion;
        this.versionNumber = versionNumber; this.generatorType = generatorType;
        this.authorInstruction = instruction; this.content = validate(content); this.status = ReviewStatus.DRAFT;
    }

    public static ChapterContractReviewVersion create(UUID id, UUID projectId, int chapterNumber, UUID contractId,
            long contractRowVersion, int versionNumber, String generatorType, String instruction,
            ChapterContractReviewContent content) {
        return new ChapterContractReviewVersion(id, projectId, chapterNumber, contractId, contractRowVersion,
                versionNumber, generatorType, instruction, content);
    }

    public void revise(ChapterContractReviewContent value) {
        if (status != ReviewStatus.DRAFT) throw new IllegalStateException("只有待处理合同审阅可以修改");
        ChapterContractReviewContent candidate = validate(value);
        if (!content.summary().equals(candidate.summary()) || content.issues().size() != candidate.issues().size()) {
            throw new IllegalArgumentException("合同审阅内容不可改写，只能更新问题处理状态");
        }
        for (int index = 0; index < content.issues().size(); index++) {
            ReviewIssue original = content.issues().get(index);
            ReviewIssue updated = candidate.issues().get(index);
            if (!java.util.Objects.equals(original.id(), updated.id())
                    || !java.util.Objects.equals(original.severity(), updated.severity())
                    || !java.util.Objects.equals(original.category(), updated.category())
                    || !java.util.Objects.equals(original.description(), updated.description())
                    || !java.util.Objects.equals(original.evidence(), updated.evidence())
                    || !java.util.Objects.equals(original.suggestion(), updated.suggestion())) {
                throw new IllegalArgumentException("合同审阅问题不可改写，只能更新处理状态");
            }
        }
        content = candidate;
    }

    public void approve() {
        if (status != ReviewStatus.DRAFT) throw new IllegalStateException("只有待处理合同审阅可以确认");
        if (content.issues().stream().anyMatch(issue -> "BLOCKING".equals(issue.severity()) && !issue.resolved())) {
            throw new IllegalArgumentException("合同审阅仍有未解决的阻断问题");
        }
        status = ReviewStatus.APPROVED;
    }

    private static ChapterContractReviewContent validate(ChapterContractReviewContent value) {
        if (value == null || value.summary() == null || value.summary().isBlank() || value.issues() == null) {
            throw new IllegalArgumentException("合同审阅摘要和问题清单不能为空");
        }
        return value;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public int getChapterNumber() { return chapterNumber; }
    public UUID getSourceContractVersionId() { return sourceContractVersionId; }
    public long getSourceContractRowVersion() { return sourceContractRowVersion; }
    public int getVersionNumber() { return versionNumber; }
    public ReviewStatus getStatus() { return status; }
    public String getGeneratorType() { return generatorType; }
    public String getAuthorInstruction() { return authorInstruction; }
    public ChapterContractReviewContent getContent() { return content; }
    public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
