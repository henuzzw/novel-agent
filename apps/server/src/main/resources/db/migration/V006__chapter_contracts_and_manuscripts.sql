CREATE TABLE chapter_contract_version (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_outline_version_id UUID NOT NULL REFERENCES outline_version(id),
    chapter_number INTEGER NOT NULL,
    version_number INTEGER NOT NULL,
    schema_version VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generator_type VARCHAR(50) NOT NULL,
    author_instruction TEXT,
    content JSONB NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_chapter_contract_version UNIQUE (project_id, chapter_number, version_number),
    CONSTRAINT ck_chapter_contract_number CHECK (chapter_number > 0)
);

CREATE INDEX idx_chapter_contract_latest
    ON chapter_contract_version (project_id, chapter_number, version_number DESC);

CREATE TABLE manuscript_version (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_contract_version_id UUID NOT NULL REFERENCES chapter_contract_version(id),
    chapter_number INTEGER NOT NULL,
    version_number INTEGER NOT NULL,
    schema_version VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generator_type VARCHAR(50) NOT NULL,
    author_instruction TEXT,
    content JSONB NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_manuscript_version UNIQUE (project_id, chapter_number, version_number),
    CONSTRAINT ck_manuscript_chapter_number CHECK (chapter_number > 0)
);

CREATE INDEX idx_manuscript_latest
    ON manuscript_version (project_id, chapter_number, version_number DESC);
