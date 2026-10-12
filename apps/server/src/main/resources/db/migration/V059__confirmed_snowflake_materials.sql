-- Material projection is produced inside step 7, not by a separate Bible generation.
ALTER TABLE snowflake_planning_run ADD COLUMN prepared_materials JSONB;
ALTER TABLE story_bible_version ADD COLUMN source_snowflake_id UUID REFERENCES snowflake_planning_run(id);
CREATE UNIQUE INDEX uq_confirmed_snowflake_direction
    ON story_bible_version(source_snowflake_id, source_direction_set_id, source_candidate_id)
    WHERE source_snowflake_id IS NOT NULL;
