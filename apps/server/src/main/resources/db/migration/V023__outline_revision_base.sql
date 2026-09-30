ALTER TABLE outline_version
    ADD COLUMN base_outline_version_id UUID REFERENCES outline_version(id);
