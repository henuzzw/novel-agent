CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE novel_project (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL,
    name VARCHAR(200) NOT NULL,
    entry_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_canon_version BIGINT NOT NULL DEFAULT 0,
    current_outline_version_id UUID,
    current_bible_version_id UUID,
    settings JSONB NOT NULL DEFAULT '{}'::jsonb,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_novel_project_name CHECK (length(trim(name)) > 0),
    CONSTRAINT ck_novel_project_canon_version CHECK (current_canon_version >= 0)
);

CREATE INDEX idx_novel_project_owner_updated
    ON novel_project (owner_id, updated_at DESC);

CREATE TABLE creative_intent (
    project_id UUID PRIMARY KEY REFERENCES novel_project(id) ON DELETE CASCADE,
    premise TEXT,
    genres JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_audience VARCHAR(300),
    protagonist_brief TEXT,
    central_conflict TEXT,
    tones JSONB NOT NULL DEFAULT '[]'::jsonb,
    target_words INTEGER,
    ending_preference VARCHAR(300),
    must_have JSONB NOT NULL DEFAULT '[]'::jsonb,
    avoid_content JSONB NOT NULL DEFAULT '[]'::jsonb,
    style_preferences JSONB NOT NULL DEFAULT '[]'::jsonb,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_creative_intent_target_words
        CHECK (target_words IS NULL OR target_words BETWEEN 1000 AND 10000000)
);

