package com.novelagent.planning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outline_version")
public class OutlineVersion {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "generation_number", nullable = false) private int generationNumber;
    @Column(name = "schema_version", nullable = false, length = 50) private String schemaVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private OutlineStatus status;
    @Column(name = "generator_type", nullable = false, length = 50) private String generatorType;
    @Column(name = "author_instruction", columnDefinition = "text") private String authorInstruction;
    @Column(name = "source_bible_version_id", nullable = false) private UUID sourceBibleVersionId;
    @Column(name = "base_outline_version_id") private UUID baseOutlineVersionId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "word_budget", nullable = false, columnDefinition = "jsonb")
    private OutlineWordBudget wordBudget;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private OutlineContent content;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "change_summary", nullable = false, columnDefinition = "jsonb")
    private List<String> changeSummary = new ArrayList<>();
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected OutlineVersion() {}

    private OutlineVersion(UUID id, UUID projectId, int generationNumber, String generatorType,
            String authorInstruction, UUID sourceBibleVersionId, UUID baseOutlineVersionId, OutlineWordBudget wordBudget,
            OutlineContent content, List<String> changeSummary) {
        if (generationNumber <= 0) throw new IllegalArgumentException("Outline generation number must be positive");
        this.id = id;
        this.projectId = projectId;
        this.generationNumber = generationNumber;
        this.schemaVersion = "outline/1";
        this.status = OutlineStatus.DRAFT;
        this.generatorType = required(generatorType, "generatorType");
        this.authorInstruction = authorInstruction;
        this.sourceBibleVersionId = sourceBibleVersionId;
        this.baseOutlineVersionId = baseOutlineVersionId;
        this.wordBudget = wordBudget;
        this.content = validate(content, wordBudget);
        this.changeSummary = changeSummary == null ? new ArrayList<>() : new ArrayList<>(changeSummary);
    }

    public static OutlineVersion create(UUID id, UUID projectId, int generationNumber, String generatorType,
            String authorInstruction, UUID sourceBibleVersionId, OutlineWordBudget wordBudget,
            OutlineContent content) {
        return create(id, projectId, generationNumber, generatorType, authorInstruction,
                sourceBibleVersionId, null, wordBudget, content, List.of());
    }

    public static OutlineVersion create(UUID id, UUID projectId, int generationNumber, String generatorType,
            String authorInstruction, UUID sourceBibleVersionId, OutlineWordBudget wordBudget,
            OutlineContent content, List<String> changeSummary) {
        return create(id, projectId, generationNumber, generatorType, authorInstruction,
                sourceBibleVersionId, null, wordBudget, content, changeSummary);
    }

    public static OutlineVersion create(UUID id, UUID projectId, int generationNumber, String generatorType,
            String authorInstruction, UUID sourceBibleVersionId, UUID baseOutlineVersionId,
            OutlineWordBudget wordBudget, OutlineContent content, List<String> changeSummary) {
        return new OutlineVersion(id, projectId, generationNumber, generatorType, authorInstruction,
                sourceBibleVersionId, baseOutlineVersionId, wordBudget, content, changeSummary);
    }

    public void revise(OutlineContent content) {
        if (status == OutlineStatus.PUBLISHED) throw new IllegalStateException("已发布的大纲不能直接修改，请生成新版本");
        this.content = validate(content, wordBudget);
    }

    public void publish() { this.status = OutlineStatus.PUBLISHED; }

    private static OutlineContent validate(OutlineContent value, OutlineWordBudget budget) {
        if (value == null) throw new IllegalArgumentException("大纲内容不能为空");
        required(value.title(), "title");
        required(value.premise(), "premise");
        if (value.arcs() == null || value.arcs().isEmpty()) throw new IllegalArgumentException("大纲至少需要一个卷或幕");
        if (value.chapterCount() == 0) throw new IllegalArgumentException("大纲至少需要一个章节计划");
        if (value.suggestedMinWords() < budget.acceptableMinWords()
                || value.suggestedMaxWords() > budget.acceptableMaxWords()
                || value.suggestedMinWords() >= value.suggestedMaxWords()) {
            throw new IllegalArgumentException("大纲建议总字数必须位于作品可接受区间内");
        }
        return value;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("大纲缺少字段：" + field);
        return value;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public int getGenerationNumber() { return generationNumber; }
    public String getSchemaVersion() { return schemaVersion; }
    public OutlineStatus getStatus() { return status; }
    public String getGeneratorType() { return generatorType; }
    public String getAuthorInstruction() { return authorInstruction; }
    public UUID getSourceBibleVersionId() { return sourceBibleVersionId; }
    public UUID getBaseOutlineVersionId() { return baseOutlineVersionId; }
    public OutlineWordBudget getWordBudget() { return wordBudget; }
    public OutlineContent getContent() { return content; }
    public List<String> getChangeSummary() { return List.copyOf(changeSummary); }
    public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
