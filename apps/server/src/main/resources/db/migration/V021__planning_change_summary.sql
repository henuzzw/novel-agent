ALTER TABLE story_direction_set
    ADD COLUMN change_summary jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE story_bible_version
    ADD COLUMN change_summary jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE outline_version
    ADD COLUMN change_summary jsonb NOT NULL DEFAULT '[]'::jsonb;
