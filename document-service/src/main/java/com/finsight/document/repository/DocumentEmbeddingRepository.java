package com.finsight.document.repository;

import com.finsight.document.entity.DocumentEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentEmbeddingRepository extends JpaRepository<DocumentEmbedding, UUID> {

    List<DocumentEmbedding> findByDocumentId(UUID documentId);

    // Native insert with explicit cast to vector type
    @Modifying
    @Query(value = """
            INSERT INTO documents.document_embeddings 
                (id, document_id, chunk_text, chunk_index, embedding, created_at)
            VALUES 
                (gen_random_uuid(), CAST(:documentId AS uuid), :chunkText, 
                 :chunkIndex, CAST(:embedding AS vector), NOW())
            """, nativeQuery = true)
    void insertEmbedding(
            @Param("documentId") String documentId,
            @Param("chunkText") String chunkText,
            @Param("chunkIndex") int chunkIndex,
            @Param("embedding") String embedding
    );

    // Semantic search
    @Query(value = """
            SELECT de.document_id::text, de.chunk_text, 
                   1 - (de.embedding <=> CAST(:embedding AS vector)) AS similarity
            FROM documents.document_embeddings de
            JOIN documents.documents d ON d.id = de.document_id
            WHERE d.user_id = CAST(:userId AS uuid)
            ORDER BY de.embedding <=> CAST(:embedding AS vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findSimilarChunks(
            @Param("embedding") String embedding,
            @Param("userId") String userId,
            @Param("limit") int limit
    );
}