ALTER TABLE agent_run
    ADD COLUMN request_snapshot JSONB,
    ADD COLUMN usage_snapshot JSONB,
    ADD COLUMN actual_input_tokens BIGINT,
    ADD COLUMN actual_output_tokens BIGINT,
    ADD COLUMN estimated_input_tokens BIGINT,
    ADD COLUMN estimated_output_tokens BIGINT;

UPDATE agent_run
   SET estimated_input_tokens = input_tokens,
       estimated_output_tokens = output_tokens
 WHERE token_source = 'ESTIMATED';

ALTER TABLE agent_run
    ADD CONSTRAINT agent_run_usage_source_check
        CHECK (token_source IN ('ACTUAL', 'ESTIMATED', 'UNKNOWN')),
    ADD CONSTRAINT agent_run_actual_usage_check
        CHECK ((token_source = 'ACTUAL' AND actual_input_tokens >= 0 AND actual_output_tokens >= 0
                AND actual_input_tokens IS NOT NULL AND actual_output_tokens IS NOT NULL)
            OR (token_source <> 'ACTUAL' AND actual_input_tokens IS NULL AND actual_output_tokens IS NULL));
