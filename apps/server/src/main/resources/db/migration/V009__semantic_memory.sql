ALTER TABLE semantic_document
    ADD COLUMN embedding_model VARCHAR(100);

CREATE INDEX idx_semantic_document_embedding_cosine
    ON semantic_document USING hnsw (embedding public.vector_cosine_ops)
    WHERE embedding IS NOT NULL;
