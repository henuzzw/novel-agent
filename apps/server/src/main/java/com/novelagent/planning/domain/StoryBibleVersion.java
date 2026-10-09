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
@Table(name = "story_bible_version")
public class StoryBibleVersion {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "generation_number", nullable = false)
    private int generationNumber;

    @Column(name = "schema_version", nullable = false, length = 50)
    private String schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StoryBibleStatus status;

    @Column(name = "generator_type", nullable = false, length = 50)
    private String generatorType;

    @Column(name = "author_instruction", columnDefinition = "text")
    private String authorInstruction;

    @Column(name = "source_direction_set_id")
    private UUID sourceDirectionSetId;

    @Column(name = "source_candidate_id")
    private UUID sourceCandidateId;

    @Column(name = "source_import_id")
    private UUID sourceImportId;

    @Column(name = "base_bible_version_id")
    private UUID baseBibleVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private StoryBibleContent content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", nullable = false, columnDefinition = "jsonb")
    private List<String> changeSummary = new ArrayList<>();

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoryBibleVersion() {
    }

    private StoryBibleVersion(UUID id, UUID projectId, int generationNumber, String generatorType,
            String authorInstruction, UUID sourceDirectionSetId, UUID sourceCandidateId,
            StoryBibleContent content, List<String> changeSummary) {
        this.id = id;
        this.projectId = projectId;
        this.generationNumber = generationNumber;
        this.schemaVersion = "story-bible/2";
        this.status = StoryBibleStatus.DRAFT;
        this.generatorType = requireText(generatorType, "generatorType");
        this.authorInstruction = authorInstruction;
        this.sourceDirectionSetId = sourceDirectionSetId;
        this.sourceCandidateId = sourceCandidateId;
        this.content = validate(content);
        this.changeSummary = changeSummary == null ? new ArrayList<>() : new ArrayList<>(changeSummary);
    }

    public static StoryBibleVersion create(UUID id, UUID projectId, int generationNumber,
            String generatorType, String authorInstruction, UUID sourceDirectionSetId,
            UUID sourceCandidateId, StoryBibleContent content) {
        return create(id, projectId, generationNumber, generatorType, authorInstruction,
                sourceDirectionSetId, sourceCandidateId, content, List.of());
    }

    public static StoryBibleVersion create(UUID id, UUID projectId, int generationNumber,
            String generatorType, String authorInstruction, UUID sourceDirectionSetId,
            UUID sourceCandidateId, StoryBibleContent content, List<String> changeSummary) {
        return create(id, projectId, generationNumber, generatorType, authorInstruction,
                sourceDirectionSetId, sourceCandidateId, null, content, changeSummary);
    }

    public static StoryBibleVersion create(UUID id, UUID projectId, int generationNumber,
            String generatorType, String authorInstruction, UUID sourceDirectionSetId,
            UUID sourceCandidateId, UUID baseBibleVersionId, StoryBibleContent content, List<String> changeSummary) {
        if (generationNumber <= 0) {
            throw new IllegalArgumentException("Story bible generation number must be positive");
        }
        StoryBibleVersion version = new StoryBibleVersion(id, projectId, generationNumber, generatorType,
                authorInstruction, sourceDirectionSetId, sourceCandidateId, content, changeSummary);
        version.baseBibleVersionId = baseBibleVersionId;
        return version;
    }

    public static StoryBibleVersion createFromImport(UUID id, UUID projectId, int generationNumber,
            String generatorType, String authorInstruction, UUID sourceImportId,
            StoryBibleContent content) {
        return createFromImport(id, projectId, generationNumber, generatorType, authorInstruction,
                sourceImportId, null, content);
    }

    public static StoryBibleVersion createFromImport(UUID id, UUID projectId, int generationNumber,
            String generatorType, String authorInstruction, UUID sourceImportId,
            UUID baseBibleVersionId, StoryBibleContent content) {
        StoryBibleVersion version = new StoryBibleVersion(id, projectId, generationNumber,
                generatorType, authorInstruction, null, null, content, List.of());
        version.sourceImportId = sourceImportId;
        version.baseBibleVersionId = baseBibleVersionId;
        return version;
    }

    public void revise(StoryBibleContent content) {
        if (status == StoryBibleStatus.PUBLISHED) {
            throw new IllegalStateException("已发布的故事圣经不能直接修改，请生成新版本");
        }
        this.content = validate(content);
        this.schemaVersion = "story-bible/2";
    }

    public void publish() {
        this.status = StoryBibleStatus.PUBLISHED;
    }

    public void linkImport(UUID importId) { this.sourceImportId = importId; }

    private static StoryBibleContent validate(StoryBibleContent value) {
        if (value == null) {
            throw new IllegalArgumentException("故事圣经内容不能为空");
        }
        requireText(value.logline(), "logline");
        requireText(value.theme(), "theme");
        requireText(value.worldSetting(), "worldSetting");
        requireText(value.protagonist(), "protagonist");
        requireText(value.centralConflict(), "centralConflict");
        requireText(value.endingDirection(), "endingDirection");
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("故事圣经缺少字段：" + field);
        }
        return value;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public int getGenerationNumber() { return generationNumber; }
    public String getSchemaVersion() { return schemaVersion; }
    public StoryBibleStatus getStatus() { return status; }
    public String getGeneratorType() { return generatorType; }
    public String getAuthorInstruction() { return authorInstruction; }
    public UUID getSourceDirectionSetId() { return sourceDirectionSetId; }
    public UUID getSourceCandidateId() { return sourceCandidateId; }
    public UUID getSourceImportId() { return sourceImportId; }
    public UUID getBaseBibleVersionId() { return baseBibleVersionId; }
    public StoryBibleContent getContent() { return content; }
    public List<String> getChangeSummary() { return List.copyOf(changeSummary); }
    public long getRowVersion() { return rowVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
