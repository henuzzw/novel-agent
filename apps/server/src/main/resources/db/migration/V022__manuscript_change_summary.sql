ALTER TABLE manuscript_version
    ADD COLUMN change_summary jsonb NOT NULL DEFAULT '[]'::jsonb;
