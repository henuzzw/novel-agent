CREATE TABLE first_three_chapters_report (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id),
    author_id UUID NOT NULL,
    version_number INTEGER NOT NULL CHECK (version_number > 0),
    fingerprint VARCHAR(64) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    review_mode VARCHAR(32) NOT NULL CHECK (review_mode IN ('MODEL', 'RULES_ONLY')),
    instruction TEXT,
    schema_version VARCHAR(50) NOT NULL,
    source JSONB NOT NULL,
    content JSONB NOT NULL,
    budget JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, author_id, version_number)
);
CREATE INDEX first_three_chapters_current_idx ON first_three_chapters_report
    (project_id, author_id, fingerprint, version_number DESC);
