DROP INDEX IF EXISTS idx_semantic_document_embedding_cosine;

ALTER TABLE semantic_document DROP COLUMN embedding;
ALTER TABLE semantic_document ADD COLUMN embedding public.vector(1024);

CREATE INDEX idx_semantic_document_embedding_cosine
    ON semantic_document USING hnsw (embedding public.vector_cosine_ops);

UPDATE semantic_document SET embedding_model = NULL;
