CREATE TABLE codex_agent_session (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    workflow_type VARCHAR(64) NOT NULL,
    thread_id VARCHAR(128) NOT NULL,
    last_turn_id VARCHAR(128),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_codex_agent_session_workflow UNIQUE (project_id, workflow_type),
    CONSTRAINT uq_codex_agent_session_thread UNIQUE (thread_id)
);

CREATE INDEX idx_codex_agent_session_project
    ON codex_agent_session (project_id, updated_at DESC);
