ALTER TABLE chapter_contract_version
    ADD COLUMN base_contract_version_id UUID REFERENCES chapter_contract_version(id);
