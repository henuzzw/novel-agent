CREATE TABLE story_direction_set (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    generation_number INTEGER NOT NULL,
    schema_version VARCHAR(50) NOT NULL,
    status VARCHAR(32) NOT NULL,
    generator_type VARCHAR(50) NOT NULL,
    author_instruction TEXT,
    input_snapshot JSONB NOT NULL,
    directions JSONB NOT NULL,
    questions_for_author JSONB NOT NULL DEFAULT '[]'::jsonb,
    selected_candidate_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_story_direction_generation UNIQUE (project_id, generation_number),
    CONSTRAINT ck_story_direction_generation CHECK (generation_number > 0),
    CONSTRAINT ck_story_direction_candidates CHECK (jsonb_array_length(directions) BETWEEN 2 AND 5)
);

CREATE INDEX idx_story_direction_project_created
    ON story_direction_set (project_id, created_at DESC);
