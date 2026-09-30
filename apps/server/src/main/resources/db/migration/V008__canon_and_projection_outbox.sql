CREATE TABLE canon_commit (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    chapter_number INTEGER NOT NULL,
    manuscript_version_id UUID NOT NULL REFERENCES manuscript_version(id),
    review_version_id UUID NOT NULL UNIQUE REFERENCES chapter_review_version(id),
    canon_version BIGINT NOT NULL,
    accepted_facts JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_canon_project_version UNIQUE (project_id, canon_version)
);

CREATE TABLE outbox_event (
    id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(200) NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_outbox_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

CREATE TABLE semantic_document (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_type VARCHAR(50) NOT NULL,
    source_id UUID NOT NULL,
    canon_version BIGINT NOT NULL,
    content TEXT NOT NULL,
    embedding public.vector(384),
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_semantic_source UNIQUE (source_type, source_id)
);

CREATE TABLE projection_checkpoint (
    event_id UUID NOT NULL,
    projection_type VARCHAR(50) NOT NULL,
    projected_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (event_id, projection_type)
);
