CREATE TABLE quality_review_version (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    chapter_number integer NOT NULL CHECK (chapter_number > 0),
    version_number integer NOT NULL CHECK (version_number > 0),
    source_manuscript_id uuid NOT NULL REFERENCES manuscript_version(id),
    source_manuscript_row_version bigint NOT NULL,
    source_outline_id uuid NOT NULL REFERENCES outline_version(id),
    source_text_hash varchar(64) NOT NULL,
    generator_type varchar(32) NOT NULL,
    author_instruction text,
    content jsonb NOT NULL,
    created_at timestamptz NOT NULL,
    UNIQUE (project_id, chapter_number, version_number)
);
CREATE INDEX quality_review_project_chapter ON quality_review_version(project_id, chapter_number, version_number DESC);
