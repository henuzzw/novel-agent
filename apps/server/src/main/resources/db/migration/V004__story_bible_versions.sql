CREATE TABLE story_bible_version (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    generation_number INTEGER NOT NULL,
    schema_version VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generator_type VARCHAR(50) NOT NULL,
    author_instruction TEXT,
    source_direction_set_id UUID NOT NULL REFERENCES story_direction_set(id),
    source_candidate_id UUID NOT NULL,
    content JSONB NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_story_bible_generation UNIQUE (project_id, generation_number),
    CONSTRAINT ck_story_bible_generation CHECK (generation_number > 0)
);

CREATE INDEX idx_story_bible_project_created
    ON story_bible_version (project_id, created_at DESC);
