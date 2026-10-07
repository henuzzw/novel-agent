CREATE TABLE user_agent_prompt (
    user_id UUID NOT NULL,
    template_key VARCHAR(80) NOT NULL,
    system_prompt TEXT CHECK (system_prompt IS NULL OR (length(trim(system_prompt)) > 0 AND length(system_prompt) <= 40000)),
    guidance TEXT NOT NULL DEFAULT '' CHECK (length(guidance) <= 40000),
    row_version BIGINT NOT NULL DEFAULT 1 CHECK (row_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, template_key)
);

CREATE TABLE user_agent_prompt_revision (
    user_id UUID NOT NULL,
    template_key VARCHAR(80) NOT NULL,
    revision BIGINT NOT NULL CHECK (revision > 0),
    system_prompt TEXT,
    guidance TEXT NOT NULL,
    operation VARCHAR(16) NOT NULL CHECK (operation IN ('SAVE', 'RESET')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, template_key, revision),
    FOREIGN KEY (user_id, template_key) REFERENCES user_agent_prompt(user_id, template_key) ON DELETE CASCADE
);

-- A changed or reset template must not reuse a Codex thread with obsolete global instructions.
ALTER TABLE codex_agent_session ADD COLUMN prompt_revision VARCHAR(100);
