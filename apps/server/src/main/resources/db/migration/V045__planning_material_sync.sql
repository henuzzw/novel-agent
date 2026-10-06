CREATE TABLE planning_character_snapshot (
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_bible_id UUID NOT NULL REFERENCES story_bible_version(id),
    character_id UUID NOT NULL REFERENCES story_entity(id),
    blueprint JSONB NOT NULL,
    PRIMARY KEY (source_bible_id, character_id)
);

CREATE TABLE planning_relationship (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES novel_project(id) ON DELETE CASCADE,
    source_bible_id UUID NOT NULL REFERENCES story_bible_version(id),
    source_key VARCHAR(100) NOT NULL,
    character_id UUID REFERENCES story_entity(id),
    description TEXT NOT NULL,
    UNIQUE (source_bible_id, source_key)
);
CREATE INDEX idx_planning_relationship_project ON planning_relationship(project_id, character_id);

ALTER TABLE reader_experience_plan ADD COLUMN source_kind VARCHAR(16)
    CHECK (source_kind IN ('BIBLE', 'OUTLINE', 'CANON'));
ALTER TABLE reader_experience_plan ADD COLUMN source_id UUID;
ALTER TABLE reader_experience_plan ADD COLUMN source_key VARCHAR(100);
ALTER TABLE reader_experience_plan ADD CONSTRAINT ck_reader_experience_origin CHECK (
    (source_kind IS NULL AND source_id IS NULL AND source_key IS NULL) OR
    (source_kind IS NOT NULL AND source_id IS NOT NULL AND source_key IS NOT NULL));
CREATE UNIQUE INDEX uq_reader_experience_origin ON reader_experience_plan(project_id, source_kind, source_id, source_key)
    WHERE source_kind IS NOT NULL;
