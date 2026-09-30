ALTER TABLE story_bible_version
    ALTER COLUMN source_direction_set_id DROP NOT NULL,
    ALTER COLUMN source_candidate_id DROP NOT NULL,
    ADD COLUMN source_import_id UUID REFERENCES work_import(id) ON DELETE SET NULL;

CREATE INDEX idx_story_bible_source_import ON story_bible_version(source_import_id)
    WHERE source_import_id IS NOT NULL;

ALTER TABLE work_import
    ADD COLUMN planning_status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    ADD COLUMN generated_bible_version_id UUID REFERENCES story_bible_version(id) ON DELETE SET NULL,
    ADD COLUMN generated_outline_version_id UUID REFERENCES outline_version(id) ON DELETE SET NULL,
    ADD COLUMN planning_error TEXT;
