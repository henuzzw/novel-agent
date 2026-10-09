ALTER TABLE snowflake_planning_run DROP CONSTRAINT snowflake_stage;
ALTER TABLE snowflake_planning_run ALTER COLUMN active_stage TYPE VARCHAR(40);
ALTER TABLE snowflake_planning_run ADD COLUMN steps JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE story_direction_set ADD COLUMN source_snowflake_id UUID REFERENCES snowflake_planning_run(id);
ALTER TABLE snowflake_planning_run ADD CONSTRAINT snowflake_stage CHECK (active_stage IN
    ('CORE','SYNOPSIS','CHARACTER_ARCS','PLOT_SUMMARY','CHARACTER_BIOGRAPHIES',
     'DETAILED_OUTLINE','CHARACTER_SETTINGS','SCENE_LIST','SCENE_EXPANSION','CHARACTERS','WORLD','PLOT'));
