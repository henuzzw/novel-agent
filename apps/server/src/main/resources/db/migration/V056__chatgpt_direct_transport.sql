CREATE TABLE user_chatgpt_transport (
    user_id uuid PRIMARY KEY,
    transport varchar(32) NOT NULL CHECK (transport IN ('APP_SERVER', 'SIWC_HTTP')),
    row_version bigint NOT NULL DEFAULT 1,
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- No OAuth credentials here. These rows contain private, project-scoped conversation data only.
CREATE TABLE model_http_conversation (
    project_id uuid NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    workflow varchar(64) NOT NULL,
    conversation_id uuid NOT NULL,
    account_binding varchar(64) NOT NULL,
    prompt_revision text,
    input_history jsonb NOT NULL DEFAULT '[]',
    lease_id uuid,
    lease_until timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, workflow)
);
