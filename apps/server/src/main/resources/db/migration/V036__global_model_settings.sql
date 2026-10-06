CREATE TABLE user_model_settings (
    user_id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('LOCAL_CODEX', 'DEEPSEEK', 'LOCAL_TEMPLATE')),
    codex_model VARCHAR(100) NOT NULL,
    codex_effort VARCHAR(16) NOT NULL,
    deepseek_model VARCHAR(100) NOT NULL,
    row_version BIGINT NOT NULL DEFAULT 1 CHECK (row_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
