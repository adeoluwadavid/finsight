package com.finsight.document.service;

import com.finsight.document.client.EmbeddingClient;
import com.finsight.document.entity.Document;
import com.finsight.document.repository.DocumentEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final EmbeddingClient embeddingClient;
    private final DocumentEmbeddingRepository documentEmbeddingRepository;

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;

    @Transactional
    public void embedDocument(Document document, String fullText) {
        log.info("Starting embedding for document: {}", document.getId());

        List<String> chunks = chunkText(fullText);
        log.info("Split document into {} chunks", chunks.size());

        int savedCount = 0;
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                float[] vector = embeddingClient.embed(chunk);
                String vectorString = vectorToString(vector);

                documentEmbeddingRepository.insertEmbedding(
                        document.getId().toString(),
                        chunk,
                        i,
                        vectorString
                );
                savedCount++;
                log.debug("Embedded chunk {}/{} for document: {}",
                        i + 1, chunks.size(), document.getId());

            } catch (Exception e) {
                log.error("Failed to embed chunk {} for document {}: {}",
                        i, document.getId(), e.getMessage());
            }
        }

        log.info("Saved {} embeddings for document: {}", savedCount, document.getId());
    }

    public float[] embedQuery(String query) {
        return embeddingClient.embed(query);
    }

    public String vectorToString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<String> chunkText(String text) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return chunks;
        }

        text = text.replaceAll("\\s+", " ").trim();

        if (text.length() <= CHUNK_SIZE) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());

            if (end < text.length()) {
                int lastPeriod = text.lastIndexOf('.', end);
                int lastNewline = text.lastIndexOf('\n', end);
                int breakPoint = Math.max(lastPeriod, lastNewline);

                if (breakPoint > start + CHUNK_SIZE / 2) {
                    end = breakPoint + 1;
                }
            }

            chunks.add(text.substring(start, end).trim());
            start = end - CHUNK_OVERLAP;

            if (start >= text.length()) break;
        }

        return chunks;
    }
}