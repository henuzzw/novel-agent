CREATE TABLE canon_memory_history (
    id UUID PRIMARY KEY,
    commit_id UUID NOT NULL REFERENCES canon_commit(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    memory_version BIGINT NOT NULL,
    action VARCHAR(32) NOT NULL,
    candidates JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (commit_id, memory_version)
);
CREATE INDEX canon_memory_history_source ON canon_memory_history(commit_id, memory_version DESC);
