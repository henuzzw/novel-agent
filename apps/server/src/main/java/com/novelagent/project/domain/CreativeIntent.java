package com.novelagent.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "creative_intent")
public class CreativeIntent {

    @Id
    @Column(name = "project_id")
    private UUID projectId;

    @Column(columnDefinition = "text")
    private String premise;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> genres = new ArrayList<>();

    @Column(name = "target_audience", length = 300)
    private String targetAudience;

    @Column(name = "protagonist_brief", columnDefinition = "text")
    private String protagonistBrief;

    @Column(name = "central_conflict", columnDefinition = "text")
    private String centralConflict;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> tones = new ArrayList<>();

    @Column(name = "target_words")
    private Integer targetWords;

    @Column(name = "ending_preference", length = 300)
    private String endingPreference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "must_have", nullable = false, columnDefinition = "jsonb")
    private List<String> mustHave = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avoid_content", nullable = false, columnDefinition = "jsonb")
    private List<String> avoid = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "style_preferences", nullable = false, columnDefinition = "jsonb")
    private List<String> stylePreferences = new ArrayList<>();

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CreativeIntent() {
    }

    public CreativeIntent(UUID projectId) {
        this.projectId = projectId;
    }

    public void update(
            String premise,
            List<String> genres,
            String targetAudience,
            String protagonistBrief,
            String centralConflict,
            List<String> tones,
            Integer targetWords,
            String endingPreference,
            List<String> mustHave,
            List<String> avoid,
            List<String> stylePreferences) {
        this.premise = premise;
        this.genres = copy(genres);
        this.targetAudience = targetAudience;
        this.protagonistBrief = protagonistBrief;
        this.centralConflict = centralConflict;
        this.tones = copy(tones);
        this.targetWords = targetWords;
        this.endingPreference = endingPreference;
        this.mustHave = copy(mustHave);
        this.avoid = copy(avoid);
        this.stylePreferences = copy(stylePreferences);
    }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<>() : new ArrayList<>(values);
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getPremise() {
        return premise;
    }

    public List<String> getGenres() {
        return List.copyOf(genres);
    }

    public String getTargetAudience() {
        return targetAudience;
    }

    public String getProtagonistBrief() {
        return protagonistBrief;
    }

    public String getCentralConflict() {
        return centralConflict;
    }

    public List<String> getTones() {
        return List.copyOf(tones);
    }

    public Integer getTargetWords() {
        return targetWords;
    }

    public String getEndingPreference() {
        return endingPreference;
    }

    public List<String> getMustHave() {
        return List.copyOf(mustHave);
    }

    public List<String> getAvoid() {
        return List.copyOf(avoid);
    }

    public List<String> getStylePreferences() {
        return List.copyOf(stylePreferences);
    }

    public long getRowVersion() {
        return rowVersion;
    }
}

