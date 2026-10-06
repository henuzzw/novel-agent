CREATE TABLE style_preview_review (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_hash varchar(64) NOT NULL,
    source jsonb NOT NULL,
    content jsonb NOT NULL,
    revision_attempted boolean NOT NULL DEFAULT false,
    row_version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL
);
CREATE INDEX style_preview_review_project ON style_preview_review(project_id, created_at DESC);
