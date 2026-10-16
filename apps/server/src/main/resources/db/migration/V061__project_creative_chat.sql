CREATE TABLE project_chat_turn (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    question TEXT NOT NULL,
    reply TEXT NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL CHECK (status IN ('RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    error_message TEXT,
    worker_identity VARCHAR(200) NOT NULL,
    sources JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (id, project_id)
);
CREATE UNIQUE INDEX project_chat_one_active ON project_chat_turn(project_id) WHERE status = 'RUNNING';
CREATE INDEX project_chat_history ON project_chat_turn(project_id, created_at DESC, id);

CREATE TABLE project_chat_change (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    turn_id UUID NOT NULL,
    source JSONB NOT NULL,
    replacement TEXT NOT NULL,
    reason TEXT NOT NULL,
    impact TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPLIED','REJECTED','UNDONE')),
    result_source JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (turn_id, project_id) REFERENCES project_chat_turn(id, project_id) ON DELETE CASCADE
);
CREATE INDEX project_chat_changes ON project_chat_change(project_id, created_at DESC, id);
