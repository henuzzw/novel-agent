ALTER TABLE semantic_document ADD COLUMN chunked_content_hash VARCHAR(64);

CREATE TABLE semantic_document_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES semantic_document(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    content TEXT NOT NULL,
    embedding public.vector(1024),
    embedding_model VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, ordinal),
    CHECK (ordinal >= 0 AND start_offset >= 0 AND end_offset > start_offset)
);
CREATE INDEX idx_semantic_chunk_cosine
    ON semantic_document_chunk USING hnsw (embedding public.vector_cosine_ops);
