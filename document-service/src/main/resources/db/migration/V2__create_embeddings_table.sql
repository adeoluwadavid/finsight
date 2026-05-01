-- Set search path so vector type is visible
SET search_path TO documents, public;

CREATE TABLE IF NOT EXISTS documents.document_embeddings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES documents.documents(id) ON DELETE CASCADE,
    chunk_text TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    embedding vector(768),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_document_embeddings_document_id
    ON documents.document_embeddings(document_id);

CREATE INDEX idx_document_embeddings_embedding
    ON documents.document_embeddings
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);