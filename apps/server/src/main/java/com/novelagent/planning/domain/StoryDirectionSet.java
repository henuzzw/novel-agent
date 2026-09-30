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
@Table(name = "story_direction_set")
public class StoryDirectionSet {

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
    private StoryDirectionStatus status;

    @Column(name = "generator_type", nullable = false, length = 50)
    private String generatorType;

    @Column(name = "author_instruction", columnDefinition = "text")
    private String authorInstruction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_snapshot", nullable = false, columnDefinition = "jsonb")
    private CreativeIntentSnapshot inputSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<StoryDirectionCandidate> directions = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions_for_author", nullable = false, columnDefinition = "jsonb")
    private List<String> questionsForAuthor = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "change_summary", nullable = false, columnDefinition = "jsonb")
    private List<String> changeSummary = new ArrayList<>();

    @Column(name = "selected_candidate_id")
    private UUID selectedCandidateId;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StoryDirectionSet() {
    }

    private StoryDirectionSet(
            UUID id,
            UUID projectId,
            int generationNumber,
            String generatorType,
            String authorInstruction,
            CreativeIntentSnapshot inputSnapshot,
            List<StoryDirectionCandidate> directions,
            List<String> questionsForAuthor,
            List<String> changeSummary) {
        if (directions == null || directions.size() < 2 || directions.size() > 5) {
            throw new IllegalArgumentException("Story direction generation must contain between 2 and 5 candidates");
        }
        this.id = id;
        this.projectId = projectId;
        this.generationNumber = generationNumber;
        this.schemaVersion = "story-directions/1";
        this.status = StoryDirectionStatus.DRAFT;
        this.generatorType = generatorType;
        this.authorInstruction = authorInstruction;
        this.inputSnapshot = inputSnapshot;
        this.directions = new ArrayList<>(directions);
        this.questionsForAuthor = questionsForAuthor == null ? new ArrayList<>() : new ArrayList<>(questionsForAuthor);
        this.changeSummary = changeSummary == null ? new ArrayList<>() : new ArrayList<>(changeSummary);
    }

    public static StoryDirectionSet create(
            UUID id,
            UUID projectId,
            int generationNumber,
            String generatorType,
            String authorInstruction,
            CreativeIntentSnapshot inputSnapshot,
            List<StoryDirectionCandidate> directions,
            List<String> questionsForAuthor) {
        return create(id, projectId, generationNumber, generatorType, authorInstruction,
                inputSnapshot, directions, questionsForAuthor, List.of());
    }

    public static StoryDirectionSet create(
            UUID id,
            UUID projectId,
            int generationNumber,
            String generatorType,
            String authorInstruction,
            CreativeIntentSnapshot inputSnapshot,
            List<StoryDirectionCandidate> directions,
            List<String> questionsForAuthor,
            List<String> changeSummary) {
        return new StoryDirectionSet(id, projectId, generationNumber, generatorType, authorInstruction,
                inputSnapshot, directions, questionsForAuthor, changeSummary);
    }

    public void select(UUID candidateId) {
        boolean exists = directions.stream().anyMatch(candidate -> candidate.id().equals(candidateId));
        if (!exists) {
            throw new IllegalArgumentException("Candidate does not belong to this direction set: " + candidateId);
        }
        this.selectedCandidateId = candidateId;
        this.status = StoryDirectionStatus.SELECTED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getGenerationNumber() {
        return generationNumber;
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public StoryDirectionStatus getStatus() {
        return status;
    }

    public String getGeneratorType() {
        return generatorType;
    }

    public String getAuthorInstruction() {
        return authorInstruction;
    }

    public CreativeIntentSnapshot getInputSnapshot() {
        return inputSnapshot;
    }

    public List<StoryDirectionCandidate> getDirections() {
        return List.copyOf(directions);
    }

    public List<String> getQuestionsForAuthor() {
        return List.copyOf(questionsForAuthor);
    }

    public List<String> getChangeSummary() {
        return List.copyOf(changeSummary);
    }

    public UUID getSelectedCandidateId() {
        return selectedCandidateId;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
