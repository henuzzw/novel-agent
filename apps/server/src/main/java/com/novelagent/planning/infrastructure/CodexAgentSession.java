package com.novelagent.planning.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "codex_agent_session")
public class CodexAgentSession {

    @Id
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "workflow_type", nullable = false, length = 64)
    private String workflowType;

    @Column(name = "thread_id", nullable = false, unique = true, length = 128)
    private String threadId;

    @Column(name = "last_turn_id", length = 128)
    private String lastTurnId;

    @Column(name = "prompt_revision", length = 100)
    private String promptRevision;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CodexAgentSession() {
    }

    private CodexAgentSession(UUID id, UUID projectId, String workflowType, String threadId) {
        this.id = id;
        this.projectId = projectId;
        this.workflowType = workflowType;
        this.threadId = threadId;
    }

    public static CodexAgentSession create(UUID projectId, String workflowType, String threadId) {
        return new CodexAgentSession(UUID.randomUUID(), projectId, workflowType, threadId);
    }

    public void replaceThread(String threadId) {
        this.threadId = threadId;
        this.lastTurnId = null;
    }

    public void recordTurn(String turnId) {
        this.lastTurnId = turnId;
    }

    public boolean matchesPromptRevision(String revision) {
        return java.util.Objects.equals(promptRevision, revision);
    }

    public void usePromptRevision(String revision) { this.promptRevision = revision; }

    public String getThreadId() {
        return threadId;
    }
}
