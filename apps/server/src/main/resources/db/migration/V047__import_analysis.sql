CREATE TABLE import_analysis_report (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    import_id UUID NOT NULL REFERENCES work_import(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('LOCAL_CODEX', 'DEEPSEEK')),
    source_hash VARCHAR(64) NOT NULL,
    slices JSONB NOT NULL,
    next_slice INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'RUNNING', 'FAILED', 'REVIEW', 'CONFIRMED', 'CANCELLED')),
    content JSONB NOT NULL DEFAULT '{"summaries":[],"items":[]}',
    decisions JSONB NOT NULL DEFAULT '[]',
    confirmed_mode VARCHAR(32) CHECK (confirmed_mode IN ('ADAPT_SOURCE', 'CONTINUE_MANUSCRIPT')),
    error_message TEXT,
    row_version BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, import_id, request_id),
    UNIQUE (id, project_id, import_id)
);
CREATE UNIQUE INDEX uq_import_analysis_running ON import_analysis_report(project_id) WHERE status = 'RUNNING';
CREATE INDEX idx_import_analysis_source ON import_analysis_report(import_id, created_at DESC);
ALTER TABLE work_import ADD COLUMN confirmed_analysis_id UUID REFERENCES import_analysis_report(id);
ALTER TABLE work_import ADD COLUMN generated_analysis_id UUID REFERENCES import_analysis_report(id);
ALTER TABLE work_import ADD COLUMN generated_analysis_version BIGINT;
