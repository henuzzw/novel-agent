package com.novelagent.project.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "novel_project")
public class NovelProject {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_mode", nullable = false, length = 32)
    private EntryMode entryMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProjectStatus status;

    @Column(name = "current_canon_version", nullable = false)
    private long currentCanonVersion;

    @Column(name = "current_outline_version_id")
    private UUID currentOutlineVersionId;

    @Column(name = "current_bible_version_id")
    private UUID currentBibleVersionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> settings = new HashMap<>();

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NovelProject() {
    }

    private NovelProject(UUID id, UUID ownerId, String name, EntryMode entryMode) {
        this.id = id;
        this.ownerId = ownerId;
        this.name = name;
        this.entryMode = entryMode;
        this.status = ProjectStatus.ACTIVE;
        this.currentCanonVersion = 0;
    }

    public static NovelProject create(UUID id, UUID ownerId, String name, EntryMode entryMode) {
        return new NovelProject(id, ownerId, name.trim(), entryMode);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public EntryMode getEntryMode() {
        return entryMode;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public long getCurrentCanonVersion() {
        return currentCanonVersion;
    }

    public long commitCanon(long expectedVersion) {
        if (currentCanonVersion != expectedVersion) {
            throw new IllegalStateException("正史版本已变化，请刷新后重试");
        }
        return ++currentCanonVersion;
    }

    public void publishStoryBible(UUID storyBibleVersionId) {
        this.currentBibleVersionId = storyBibleVersionId;
    }

    public UUID getCurrentBibleVersionId() {
        return currentBibleVersionId;
    }

    public void publishOutline(UUID outlineVersionId) {
        this.currentOutlineVersionId = outlineVersionId;
    }

    public UUID getCurrentOutlineVersionId() {
        return currentOutlineVersionId;
    }

    public long getRowVersion() {
        return rowVersion;
    }

    public Object getSetting(String key) {
        return settings.get(key);
    }

    public void setSetting(String key, Map<String, Object> value) {
        Map<String, Object> updated = new HashMap<>(settings);
        if (value == null) updated.remove(key);
        else updated.put(key, new HashMap<>(value));
        settings = updated;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
