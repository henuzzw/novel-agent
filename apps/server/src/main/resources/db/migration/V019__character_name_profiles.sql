ALTER TABLE story_entity ALTER COLUMN source_commit_id DROP NOT NULL;
ALTER TABLE story_entity ADD COLUMN role_key VARCHAR(80);
ALTER TABLE story_entity ADD COLUMN source_name VARCHAR(200);
ALTER TABLE story_entity ADD COLUMN nickname VARCHAR(200);
ALTER TABLE story_entity ADD COLUMN title_name VARCHAR(200);
ALTER TABLE story_entity ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX uq_story_entity_role
    ON story_entity(project_id, role_key)
    WHERE role_key IS NOT NULL AND canon_version_to IS NULL;

UPDATE story_entity SET source_name = canonical_name WHERE source_name IS NULL;
