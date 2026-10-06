ALTER TABLE canon_commit ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE canon_commit ADD COLUMN superseded_by_commit_id UUID;
ALTER TABLE canon_commit ADD CONSTRAINT fk_canon_superseded_by
    FOREIGN KEY (superseded_by_commit_id) REFERENCES canon_commit(id)
    DEFERRABLE INITIALLY DEFERRED;

-- Older installations may contain more than one commit per chapter. Keep the newest active.
WITH ranked AS (
    SELECT id, row_number() OVER (PARTITION BY project_id, chapter_number ORDER BY canon_version DESC) AS rank
    FROM canon_commit
)
UPDATE canon_commit c SET active = FALSE
FROM ranked r WHERE c.id = r.id AND r.rank > 1;

CREATE UNIQUE INDEX uq_canon_commit_active_chapter
    ON canon_commit(project_id, chapter_number) WHERE active;
