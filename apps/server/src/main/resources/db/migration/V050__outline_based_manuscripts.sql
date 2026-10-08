ALTER TABLE manuscript_version ALTER COLUMN source_contract_version_id DROP NOT NULL;
ALTER TABLE manuscript_version ADD COLUMN writing_basis jsonb;
ALTER TABLE manuscript_version ADD CONSTRAINT manuscript_writing_source_required
    CHECK (source_contract_version_id IS NOT NULL OR
        (writing_basis IS NOT NULL AND writing_basis->>'outlineId' IS NOT NULL
         AND writing_basis->>'fingerprint' IS NOT NULL AND writing_basis->'plan' IS NOT NULL));
