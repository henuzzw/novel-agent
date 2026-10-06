CREATE TABLE chapter_contract_review_version (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    chapter_number INTEGER NOT NULL,
    source_contract_version_id UUID NOT NULL REFERENCES chapter_contract_version(id),
    source_contract_row_version BIGINT NOT NULL,
    version_number INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    generator_type VARCHAR(50) NOT NULL,
    author_instruction TEXT,
    content JSONB NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_chapter_contract_review_version UNIQUE (project_id, chapter_number, version_number)
);

CREATE INDEX idx_chapter_contract_review_latest
    ON chapter_contract_review_version (project_id, chapter_number, version_number DESC);
