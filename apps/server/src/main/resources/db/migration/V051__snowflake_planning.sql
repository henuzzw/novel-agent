CREATE TABLE snowflake_planning_run (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    mode VARCHAR(40) NOT NULL,
    provider VARCHAR(40) NOT NULL,
    input_snapshot JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    active_stage VARCHAR(20) NOT NULL DEFAULT 'CORE',
    core TEXT,
    characters TEXT,
    world TEXT,
    plot TEXT,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT snowflake_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT snowflake_stage CHECK (active_stage IN ('CORE', 'CHARACTERS', 'WORLD', 'PLOT'))
);
CREATE INDEX snowflake_project_latest ON snowflake_planning_run(project_id, created_at DESC, id);
