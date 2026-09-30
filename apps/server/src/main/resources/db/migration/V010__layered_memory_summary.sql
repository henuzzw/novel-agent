ALTER TABLE semantic_document
    ADD COLUMN summary TEXT;

UPDATE semantic_document d
   SET summary = m.content ->> 'summary'
  FROM manuscript_version m
 WHERE m.id = d.source_id
   AND d.source_type = 'MANUSCRIPT';

UPDATE semantic_document
   SET summary = left(content, 500)
 WHERE summary IS NULL OR btrim(summary) = '';

ALTER TABLE semantic_document
    ALTER COLUMN summary SET NOT NULL;
