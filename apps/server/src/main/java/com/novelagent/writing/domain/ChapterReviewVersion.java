package com.novelagent.writing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chapter_review_version")
public class ChapterReviewVersion {
    private static final Set<String> PRONOUNS = Set.of(
            "他", "她", "它", "他们", "她们", "它们", "自己", "对方", "那个人", "这个人");
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "chapter_number", nullable = false) private int chapterNumber;
    @Column(name = "source_manuscript_version_id", nullable = false) private UUID sourceManuscriptVersionId;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(name = "schema_version", nullable = false, length = 50) private String schemaVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ReviewStatus status;
    @Column(name = "generator_type", nullable = false, length = 50) private String generatorType;
    @Column(name = "author_instruction", columnDefinition = "text") private String authorInstruction;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private ChapterReviewContent content;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ChapterReviewVersion() {}
    private ChapterReviewVersion(UUID id, UUID projectId, int chapterNumber, UUID manuscriptId, int versionNumber,
            String generatorType, String instruction, ChapterReviewContent content) {
        this.id = id; this.projectId = projectId; this.chapterNumber = chapterNumber;
        this.sourceManuscriptVersionId = manuscriptId; this.versionNumber = versionNumber;
        this.schemaVersion = "chapter-review/2"; this.status = ReviewStatus.DRAFT;
        this.generatorType = generatorType; this.authorInstruction = instruction; this.content = validate(content);
    }
    public static ChapterReviewVersion create(UUID id, UUID projectId, int chapterNumber, UUID manuscriptId,
            int versionNumber, String generatorType, String instruction, ChapterReviewContent content) {
        return new ChapterReviewVersion(id, projectId, chapterNumber, manuscriptId, versionNumber,
                generatorType, instruction, content);
    }
    public void revise(ChapterReviewContent value) {
        if (status != ReviewStatus.DRAFT) throw new IllegalStateException("只有待处理审稿可以修改");
        content = validate(value);
    }
    public void approve() {
        if (status != ReviewStatus.DRAFT) throw new IllegalStateException("只有待处理审稿可以确认");
        if (content.issues().stream().anyMatch(issue -> "BLOCKING".equals(issue.severity()) && !issue.resolved()))
            throw new IllegalArgumentException("仍有未解决的阻断问题");
        if (content.factProposals().stream().anyMatch(fact -> fact.decision() == FactDecision.PENDING))
            throw new IllegalArgumentException("候选事实尚未全部接受或拒绝");
        if (content.factProposals().stream()
                .filter(fact -> fact.decision() == FactDecision.ACCEPTED)
                .anyMatch(this::hasUnresolvedPronoun))
            throw new IllegalArgumentException("接受的候选事实仍有未解析的人物代词，请先选择具体实体");
        status = ReviewStatus.APPROVED;
    }
    public void returnForRewrite() {
        if (status != ReviewStatus.DRAFT) throw new IllegalStateException("只有待处理审稿可以打回正文");
        status = ReviewStatus.RETURNED;
    }
    private static ChapterReviewContent validate(ChapterReviewContent value) {
        if (value == null || value.summary() == null || value.summary().isBlank())
            throw new IllegalArgumentException("审稿摘要不能为空");
        if (value.issues() == null || value.factProposals() == null)
            throw new IllegalArgumentException("审稿问题和候选事实不能为空");
        return value;
    }
    private boolean hasUnresolvedPronoun(FactProposal fact) {
        TypedFactPayload payload = fact.payload();
        if (payload == null) return false;
        return switch (fact.factType()) {
            case "ENTITY_UPSERT", "STATE_CHANGE" -> pronounWithoutId(
                    firstText(payload.entityName(), payload.characterName(), fact.subject()), payload.entityId());
            case "RELATION_CHANGE" -> pronounWithoutId(
                    firstText(payload.sourceEntityName(), fact.subject()), payload.sourceEntityId())
                    || pronounWithoutId(firstText(payload.targetEntityName(), fact.object()), payload.targetEntityId());
            case "KNOWLEDGE_CHANGE" -> pronounWithoutId(
                    firstText(payload.characterName(), fact.subject()), payload.characterId());
            default -> false;
        };
    }
    private boolean pronounWithoutId(String mention, String entityId) {
        return mention != null && PRONOUNS.contains(mention.strip())
                && (entityId == null || entityId.isBlank());
    }
    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
    public UUID getId() { return id; } public UUID getProjectId() { return projectId; }
    public int getChapterNumber() { return chapterNumber; } public UUID getSourceManuscriptVersionId() { return sourceManuscriptVersionId; }
    public int getVersionNumber() { return versionNumber; } public String getSchemaVersion() { return schemaVersion; }
    public ReviewStatus getStatus() { return status; } public String getGeneratorType() { return generatorType; }
    public String getAuthorInstruction() { return authorInstruction; } public ChapterReviewContent getContent() { return content; }
    public long getRowVersion() { return rowVersion; } public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
