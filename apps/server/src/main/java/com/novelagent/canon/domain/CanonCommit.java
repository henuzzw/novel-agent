package com.novelagent.canon.domain;

import com.novelagent.writing.domain.FactProposal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "canon_commit")
public class CanonCommit {
    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "chapter_number", nullable = false)
    private int chapterNumber;

    @Column(name = "manuscript_version_id", nullable = false)
    private UUID manuscriptVersionId;

    @Column(name = "review_version_id", unique = true)
    private UUID reviewVersionId;

    @Column(name = "canon_version", nullable = false)
    private long canonVersion;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "superseded_by_commit_id")
    private UUID supersededByCommitId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "accepted_facts", nullable = false, columnDefinition = "jsonb")
    private List<FactProposal> acceptedFacts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CanonCommit() {
    }

    public CanonCommit(UUID id, UUID projectId, int chapterNumber, UUID manuscriptVersionId, UUID reviewVersionId,
            long canonVersion, List<FactProposal> acceptedFacts) {
        this.id = id;
        this.projectId = projectId;
        this.chapterNumber = chapterNumber;
        this.manuscriptVersionId = manuscriptVersionId;
        this.reviewVersionId = reviewVersionId;
        this.canonVersion = canonVersion;
        this.acceptedFacts = List.copyOf(acceptedFacts);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getChapterNumber() {
        return chapterNumber;
    }

    public UUID getManuscriptVersionId() {
        return manuscriptVersionId;
    }

    public UUID getReviewVersionId() {
        return reviewVersionId;
    }

    public long getCanonVersion() {
        return canonVersion;
    }

    public List<FactProposal> getAcceptedFacts() {
        return acceptedFacts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isActive() {
        return active;
    }

    public UUID getSupersededByCommitId() {
        return supersededByCommitId;
    }

    public void supersede(UUID replacementId) {
        if (!active) throw new IllegalStateException("这条正史已被替换");
        active = false;
        supersededByCommitId = replacementId;
    }

    /** Derived memory can be added only while this published source remains active. */
    public void addMemoryFacts(List<FactProposal> facts) {
        if (!active) throw new IllegalStateException("这条正史已被替换");
        var combined = new java.util.ArrayList<>(acceptedFacts);
        for (var fact : facts) {
            if (combined.stream().anyMatch(existing -> existing.id().equals(fact.id()))) {
                throw new IllegalStateException("记忆条目已保存");
            }
            combined.add(fact);
        }
        acceptedFacts = List.copyOf(combined);
    }
}
