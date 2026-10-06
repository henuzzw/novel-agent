ALTER TABLE story_bible_version
    ADD COLUMN base_bible_version_id UUID REFERENCES story_bible_version(id);
