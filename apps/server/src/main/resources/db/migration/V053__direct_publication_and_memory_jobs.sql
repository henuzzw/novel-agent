ALTER TABLE canon_commit ALTER COLUMN review_version_id DROP NOT NULL;

CREATE TABLE canon_memory_job (
    commit_id UUID PRIMARY KEY REFERENCES canon_commit(id),
    project_id UUID NOT NULL REFERENCES novel_project(id),
    provider VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    candidates JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_body TEXT,
    error_message TEXT,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX canon_memory_job_pending ON canon_memory_job(created_at) WHERE status = 'PENDING';
