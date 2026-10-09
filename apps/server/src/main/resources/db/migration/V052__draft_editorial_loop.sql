CREATE TABLE draft_loop_run (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES novel_project(id),
    request_key uuid NOT NULL,
    chapter_number integer NOT NULL CHECK (chapter_number > 0),
    provider varchar(40) NOT NULL,
    write_first boolean NOT NULL,
    max_rounds integer NOT NULL CHECK (max_rounds BETWEEN 1 AND 10),
    status varchar(30) NOT NULL,
    phase varchar(10) NOT NULL,
    stop_reason varchar(40),
    basis jsonb NOT NULL,
    manuscript_id uuid,
    manuscript_row_version bigint NOT NULL DEFAULT 0,
    rounds jsonb NOT NULL,
    body_hashes jsonb NOT NULL,
    error_message text,
    worker_identity varchar(512),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    UNIQUE (project_id, request_key)
);
CREATE INDEX draft_loop_chapter_idx ON draft_loop_run(project_id, chapter_number, created_at DESC);
CREATE UNIQUE INDEX draft_loop_active_project_idx ON draft_loop_run(project_id) WHERE status IN ('PENDING', 'RUNNING');
