ALTER TABLE planning_checkpoint ADD COLUMN dependencies JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE planning_batch (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    source JSONB NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    checkpoint_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    outline_version_id UUID REFERENCES outline_version(id),
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_planning_batch_request UNIQUE (project_id, request_id),
    CONSTRAINT ck_planning_batch_status CHECK (status IN ('READY', 'RUNNING', 'FAILED', 'CANCELLED', 'SUCCEEDED')),
    CONSTRAINT ck_planning_batch_version CHECK (row_version >= 0),
    CONSTRAINT ck_planning_batch_outline CHECK ((status = 'SUCCEEDED') = (outline_version_id IS NOT NULL))
);
CREATE INDEX idx_planning_batch_project_created ON planning_batch(project_id, created_at DESC);
