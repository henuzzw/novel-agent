CREATE TABLE creation_preparation_task (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL CHECK (mode IN ('PREPARE', 'REVIEW')),
    provider VARCHAR(32) NOT NULL,
    instruction TEXT NOT NULL,
    source_bible_id UUID NOT NULL REFERENCES story_bible_version(id),
    source_outline_id UUID NOT NULL REFERENCES outline_version(id),
    source_hash VARCHAR(64) NOT NULL,
    source_snapshot JSONB NOT NULL,
    start_chapter INTEGER NOT NULL CHECK (start_chapter > 0),
    end_chapter INTEGER NOT NULL CHECK (end_chapter >= start_chapter),
    status VARCHAR(32) NOT NULL CHECK (status IN ('READY', 'RUNNING', 'FAILED', 'AWAITING_CONFIRMATION', 'CONFIRMED', 'CANCELLED')),
    next_step INTEGER NOT NULL CHECK (next_step BETWEEN 0 AND 3),
    world_design JSONB,
    plot_design JSONB,
    review_report JSONB,
    result_outline_id UUID REFERENCES outline_version(id),
    error_message TEXT,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (project_id, request_id)
);
CREATE INDEX idx_creation_preparation_project ON creation_preparation_task(project_id, created_at DESC);
CREATE UNIQUE INDEX idx_creation_preparation_running ON creation_preparation_task(project_id) WHERE status = 'RUNNING';

CREATE TABLE creation_preparation_current (
    project_id UUID PRIMARY KEY REFERENCES novel_project(id) ON DELETE CASCADE,
    task_id UUID NOT NULL REFERENCES creation_preparation_task(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE reader_experience_plan DROP CONSTRAINT reader_experience_plan_source_kind_check;
ALTER TABLE reader_experience_plan ADD CONSTRAINT reader_experience_plan_source_kind_check
    CHECK (source_kind IN ('BIBLE', 'OUTLINE', 'CANON', 'PREPARATION'));

CREATE TABLE chapter_plan_link (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    plan_id UUID NOT NULL,
    source_commit_id UUID NOT NULL REFERENCES canon_commit(id),
    source_fact_id UUID NOT NULL REFERENCES story_fact(id),
    plan_row_version BIGINT NOT NULL CHECK (plan_row_version >= 0),
    proposed_state VARCHAR(24) NOT NULL CHECK (proposed_state IN ('SET_UP', 'REINFORCED', 'PAYOFF', 'OPEN', 'ABANDONED')),
    evidence TEXT NOT NULL,
    evidence_tokenized TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (plan_id, project_id) REFERENCES reader_experience_plan(id, project_id),
    UNIQUE (plan_id, source_fact_id)
);
