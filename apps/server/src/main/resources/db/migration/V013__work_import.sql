CREATE TABLE work_import (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    original_filename VARCHAR(500) NOT NULL,
    media_type VARCHAR(200),
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    parser_version VARCHAR(100) NOT NULL,
    detected_content_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    original_content BYTEA NOT NULL,
    extracted_text TEXT NOT NULL,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,
    CONSTRAINT uq_work_import_hash UNIQUE (project_id, sha256)
);

CREATE INDEX idx_work_import_project_created
    ON work_import (project_id, created_at DESC);

CREATE TABLE imported_chapter (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES work_import(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    character_count INTEGER NOT NULL,
    content_type VARCHAR(32) NOT NULL,
    selected BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_imported_chapter_ordinal UNIQUE (import_id, ordinal)
);

CREATE INDEX idx_imported_chapter_project
    ON imported_chapter (project_id, import_id, ordinal);
