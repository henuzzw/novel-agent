CREATE TABLE outline_version (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    generation_number INTEGER NOT NULL,
    schema_version VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generator_type VARCHAR(50) NOT NULL,
    author_instruction TEXT,
    source_bible_version_id UUID NOT NULL REFERENCES story_bible_version(id),
    word_budget JSONB NOT NULL,
    content JSONB NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_outline_generation UNIQUE (project_id, generation_number),
    CONSTRAINT ck_outline_generation CHECK (generation_number > 0)
);

CREATE INDEX idx_outline_project_created ON outline_version (project_id, created_at DESC);
