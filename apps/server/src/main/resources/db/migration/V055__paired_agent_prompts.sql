ALTER TABLE user_agent_prompt ADD COLUMN session_system_prompt text;
ALTER TABLE user_agent_prompt_revision ADD COLUMN session_system_prompt text;

ALTER TABLE user_agent_prompt ADD CONSTRAINT prompt_session_system_length
    CHECK (session_system_prompt IS NULL OR (length(btrim(session_system_prompt)) > 0 AND length(session_system_prompt) <= 40000));
ALTER TABLE user_agent_prompt_revision ADD CONSTRAINT prompt_revision_session_system_length
    CHECK (session_system_prompt IS NULL OR (length(btrim(session_system_prompt)) > 0 AND length(session_system_prompt) <= 40000));
