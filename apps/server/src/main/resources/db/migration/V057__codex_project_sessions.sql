ALTER TABLE user_chatgpt_transport DROP CONSTRAINT user_chatgpt_transport_transport_check;
ALTER TABLE user_chatgpt_transport ADD CONSTRAINT user_chatgpt_transport_transport_check
    CHECK (transport IN ('APP_SERVER', 'SIWC_HTTP', 'SIWC_CODEX'));

-- OAuth credentials remain outside PostgreSQL. This table holds private manuscript references.
CREATE TABLE model_codex_conversation (
    project_id uuid NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    workflow varchar(64) NOT NULL,
    account_binding varchar(64) NOT NULL,
    thread_id text,
    sent_materials jsonb NOT NULL DEFAULT '[]',
    context_tokens bigint NOT NULL DEFAULT 0,
    lease_id uuid,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, workflow)
);
CREATE TABLE model_codex_material (
    project_id uuid NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    material_id varchar(64) NOT NULL,
    content text NOT NULL,
    PRIMARY KEY (project_id, material_id)
);
