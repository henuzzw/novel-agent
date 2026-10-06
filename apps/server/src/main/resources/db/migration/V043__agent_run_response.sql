ALTER TABLE agent_run
    ADD COLUMN response_text TEXT,
    ADD COLUMN response_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN error_type VARCHAR(120),
    ADD COLUMN error_category VARCHAR(40),
    ADD COLUMN error_detail TEXT;

COMMENT ON COLUMN agent_run.response_text IS 'Private model response or incomplete output; never a published business artifact';
