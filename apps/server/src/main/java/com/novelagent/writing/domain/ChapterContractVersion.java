package com.novelagent.writing.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "chapter_contract_version")
public class ChapterContractVersion {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "source_outline_version_id", nullable = false) private UUID sourceOutlineVersionId;
    @Column(name = "base_contract_version_id") private UUID baseContractVersionId;
    @Column(name = "chapter_number", nullable = false) private int chapterNumber;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(name = "schema_version", nullable = false, length = 50) private String schemaVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ChapterContractStatus status;
    @Column(name = "generator_type", nullable = false, length = 50) private String generatorType;
    @Column(name = "author_instruction", columnDefinition = "text") private String authorInstruction;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private ChapterContractContent content;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected ChapterContractVersion() {}
    private ChapterContractVersion(UUID id, UUID projectId, UUID outlineId, int chapterNumber, int versionNumber,
            String generatorType, String instruction, UUID baseContractVersionId, ChapterContractContent content) {
        this.id = id; this.projectId = projectId; this.sourceOutlineVersionId = outlineId;
        this.baseContractVersionId = baseContractVersionId;
        this.chapterNumber = chapterNumber; this.versionNumber = versionNumber; this.schemaVersion = "chapter-contract/1";
        this.status = ChapterContractStatus.DRAFT; this.generatorType = required(generatorType, "generatorType");
        this.authorInstruction = instruction; this.content = validate(content);
    }
    public static ChapterContractVersion create(UUID id, UUID projectId, UUID outlineId, int chapterNumber,
            int versionNumber, String generatorType, String instruction, ChapterContractContent content) {
        if (chapterNumber <= 0 || versionNumber <= 0) throw new IllegalArgumentException("章节号和版本号必须为正数");
        return new ChapterContractVersion(id, projectId, outlineId, chapterNumber, versionNumber,
                generatorType, instruction, null, content);
    }
    public static ChapterContractVersion create(UUID id, UUID projectId, UUID outlineId, int chapterNumber,
            int versionNumber, String generatorType, String instruction, UUID baseContractVersionId,
            ChapterContractContent content) {
        if (chapterNumber <= 0 || versionNumber <= 0) throw new IllegalArgumentException("章节号和版本号必须为正数");
        return new ChapterContractVersion(id, projectId, outlineId, chapterNumber, versionNumber,
                generatorType, instruction, baseContractVersionId, content);
    }
    public void revise(ChapterContractContent value) {
        if (status == ChapterContractStatus.APPROVED) throw new IllegalStateException("已确认的章节合同不能修改，请生成新版本");
        content = validate(value);
    }
    public void approve() { status = ChapterContractStatus.APPROVED; }
    private static ChapterContractContent validate(ChapterContractContent value) {
        if (value == null) throw new IllegalArgumentException("章节合同不能为空");
        required(value.chapterTitle(), "chapterTitle"); required(value.pov(), "pov");
        required(value.objective(), "objective"); required(value.hook(), "hook");
        if (value.suggestedMinWords() <= 0 || value.suggestedMaxWords() <= value.suggestedMinWords())
            throw new IllegalArgumentException("章节建议字数区间无效");
        return value;
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("章节合同缺少字段：" + field);
        return value;
    }
    public UUID getId() { return id; } public UUID getProjectId() { return projectId; }
    public UUID getSourceOutlineVersionId() { return sourceOutlineVersionId; }
    public UUID getBaseContractVersionId() { return baseContractVersionId; }
    public int getChapterNumber() { return chapterNumber; } public int getVersionNumber() { return versionNumber; }
    public String getSchemaVersion() { return schemaVersion; } public ChapterContractStatus getStatus() { return status; }
    public String getGeneratorType() { return generatorType; } public String getAuthorInstruction() { return authorInstruction; }
    public ChapterContractContent getContent() { return content; } public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
