package com.finsight.conversation.service;

import com.finsight.conversation.client.EmbeddingClient;
import com.finsight.conversation.client.GroqClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagService {

    private final EmbeddingClient embeddingClient;
    private final GroqClient groqClient;
    private final JdbcTemplate jdbcTemplate;

    @Value("${finsight.rag.max-context-chunks:5}")
    private int maxContextChunks;

    public String answer(String userQuestion, String userId,
                         List<Map<String, String>> conversationHistory) {

        log.info("RAG query for user: {}, question: {}", userId, userQuestion);

        // Step 1: Embed the user question
        float[] queryVector = embeddingClient.embed(userQuestion);
        String vectorString = embeddingClient.vectorToString(queryVector);

        // Step 2: Retrieve relevant chunks via vector similarity search
        List<Map<String, Object>> relevantChunks = retrieveRelevantChunks(
                vectorString, userId);

        // Step 3: Also retrieve structured invoice data via SQL
        List<Map<String, Object>> invoiceData = retrieveInvoiceData(userId);

        // Step 4: Assemble context
        String context = assembleContext(relevantChunks, invoiceData);

        // Step 5: Build messages for Groq
        List<Map<String, String>> messages = buildMessages(
                userQuestion, context, conversationHistory);

        // Step 6: Call Groq
        String answer = groqClient.chat(messages);
        log.info("RAG answer generated for user: {}", userId);

        return answer;
    }

    private List<Map<String, Object>> retrieveRelevantChunks(
            String vectorString, String userId) {
        try {
            String sql = """
                    SET search_path TO documents, public;
                    SELECT de.chunk_text,
                           ei.vendor_name,
                           ei.total,
                           ei.currency,
                           ei.category,
                           1 - (de.embedding <=> CAST(? AS vector)) AS similarity
                    FROM documents.document_embeddings de
                    JOIN documents.documents d ON d.id = de.document_id
                    JOIN documents.extracted_invoices ei ON ei.document_id = d.id
                    WHERE d.user_id = CAST(? AS uuid)
                    ORDER BY de.embedding <=> CAST(? AS vector)
                    LIMIT ?
                    """;

            return jdbcTemplate.queryForList(sql,
                    vectorString, userId, vectorString, maxContextChunks);
        } catch (Exception e) {
            log.error("Error retrieving relevant chunks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<Map<String, Object>> retrieveInvoiceData(String userId) {
        try {
            String sql = """
                    SELECT ei.vendor_name, ei.invoice_number, ei.issue_date,
                           ei.total, ei.currency, ei.category,
                           ei.subtotal, ei.tax
                    FROM documents.extracted_invoices ei
                    JOIN documents.documents d ON d.id = ei.document_id
                    WHERE d.user_id = CAST(? AS uuid)
                    ORDER BY ei.issue_date DESC
                    LIMIT 20
                    """;

            return jdbcTemplate.queryForList(sql, userId);
        } catch (Exception e) {
            log.error("Error retrieving invoice data: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String assembleContext(List<Map<String, Object>> chunks,
                                   List<Map<String, Object>> invoices) {
        StringBuilder context = new StringBuilder();

        if (!invoices.isEmpty()) {
            context.append("=== INVOICE SUMMARY ===\n");
            for (Map<String, Object> invoice : invoices) {
                context.append(String.format(
                        "Vendor: %s | Date: %s | Total: %s %s | Category: %s\n",
                        invoice.get("vendor_name"),
                        invoice.get("issue_date"),
                        invoice.get("total"),
                        invoice.get("currency"),
                        invoice.get("category")
                ));
            }
            context.append("\n");
        }

        if (!chunks.isEmpty()) {
            context.append("=== RELEVANT DOCUMENT EXCERPTS ===\n");
            for (Map<String, Object> chunk : chunks) {
                context.append(chunk.get("chunk_text")).append("\n---\n");
            }
        }

        return context.toString();
    }

    private List<Map<String, String>> buildMessages(String userQuestion,
                                                    String context, List<Map<String, String>> history) {

        List<Map<String, String>> messages = new ArrayList<>();

        // System prompt
        Map<String, String> systemMessage = new HashMap<>();
        systemMessage.put("role", "system");
        systemMessage.put("content", """
                You are FinSight AI, an intelligent financial assistant for Nigerian SMEs.
                You help business owners understand their invoices, track spending, and
                gain financial insights.
                
                Answer questions based ONLY on the provided financial data context.
                If the data doesn't contain enough information to answer, say so clearly.
                Always mention specific amounts, vendors, and dates when relevant.
                Format currency as NGN amounts with commas (e.g., NGN 220,375).
                Be concise, accurate, and helpful.
                
                Financial Context:
                """ + context);
        messages.add(systemMessage);

        // Add recent conversation history (last 6 messages for context)
        int historyStart = Math.max(0, history.size() - 6);
        messages.addAll(history.subList(historyStart, history.size()));

        // Add current user question
        Map<String, String> userMessage = new HashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", userQuestion);
        messages.add(userMessage);

        return messages;
    }
}