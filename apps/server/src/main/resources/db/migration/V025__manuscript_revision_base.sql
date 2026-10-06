ALTER TABLE manuscript_version
    ADD COLUMN base_manuscript_version_id UUID REFERENCES manuscript_version(id);
