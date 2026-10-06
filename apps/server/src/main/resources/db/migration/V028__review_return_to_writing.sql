ALTER TABLE manuscript_version
    ADD COLUMN source_review_version_id UUID REFERENCES chapter_review_version(id);

CREATE UNIQUE INDEX uq_manuscript_source_review
    ON manuscript_version(source_review_version_id)
    WHERE source_review_version_id IS NOT NULL;
