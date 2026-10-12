-- Direction selection is no longer part of planning. One material snapshot per confirmed run.
CREATE UNIQUE INDEX uq_confirmed_snowflake_plan
    ON story_bible_version(project_id, source_snowflake_id)
    WHERE source_snowflake_id IS NOT NULL AND source_direction_set_id IS NULL;
