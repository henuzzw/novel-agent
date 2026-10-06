CREATE TABLE automation_run (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES novel_project(id),
    outline_id uuid NOT NULL REFERENCES outline_version(id),
    request_key uuid NOT NULL,
    first_chapter integer NOT NULL,
    last_chapter integer NOT NULL,
    current_chapter integer NOT NULL,
    provider varchar(32) NOT NULL,
    instruction text,
    status varchar(32) NOT NULL,
    cancel_requested boolean NOT NULL DEFAULT false,
    attempt integer NOT NULL DEFAULT 0,
    waiting_reason text,
    error_code varchar(100),
    steps jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    row_version bigint NOT NULL DEFAULT 0,
    CHECK (first_chapter > 0 AND last_chapter >= first_chapter AND last_chapter - first_chapter < 20),
    CHECK (current_chapter BETWEEN first_chapter AND last_chapter),
    CHECK (status IN ('PENDING', 'RUNNING', 'WAITING_FOR_USER', 'FAILED', 'CANCELLED', 'SUCCEEDED'))
);
CREATE UNIQUE INDEX automation_run_request_key ON automation_run(project_id, request_key);
CREATE INDEX automation_run_project_created ON automation_run(project_id, created_at DESC);
CREATE UNIQUE INDEX automation_run_one_active_project ON automation_run(project_id)
    WHERE status IN ('PENDING', 'RUNNING', 'WAITING_FOR_USER', 'FAILED');
