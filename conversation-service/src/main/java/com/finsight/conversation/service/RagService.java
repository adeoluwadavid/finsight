package com.finsight.conversation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.conversation.client.EmbeddingClient;
import com.finsight.conversation.client.GroqClient;
import com.finsight.conversation.dto.GroqResponse;
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
    private final ObjectMapper objectMapper;

    @Value("${finsight.rag.max-context-chunks:5}")
    private int maxContextChunks;

    public GroqResponse answer(String userQuestion, String userId,
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
        String rawResponse = groqClient.chat(messages);
        log.info("RAG response received for user: {}", userId);

        // Step 7: Parse response into text + chart spec
        return parseResponse(rawResponse);
    }

    private GroqResponse parseResponse(String rawResponse) {
        try {
            // Look for JSON block in the response
            int jsonStart = rawResponse.indexOf("```json");
            int jsonEnd = rawResponse.lastIndexOf("```");

            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                String textPart = rawResponse.substring(0, jsonStart).trim();
                String jsonPart = rawResponse
                        .substring(jsonStart + 7, jsonEnd).trim();

                // Validate it's a chart spec
                JsonNode json = objectMapper.readTree(jsonPart);
                if (json.has("type") && json.has("labels") && json.has("datasets")) {
                    log.info("Chart spec extracted from response");
                    return GroqResponse.builder()
                            .textAnswer(textPart.isEmpty() ? extractTextAnswer(rawResponse) : textPart)
                            .chartSpec(jsonPart)
                            .build();
                }
            }

            // No chart spec found — plain text answer
            return GroqResponse.builder()
                    .textAnswer(rawResponse)
                    .chartSpec(null)
                    .build();

        } catch (Exception e) {
            log.warn("Could not parse chart spec from response: {}", e.getMessage());
            return GroqResponse.builder()
                    .textAnswer(rawResponse)
                    .chartSpec(null)
                    .build();
        }
    }

    private String extractTextAnswer(String response) {
        // Remove any JSON blocks and return clean text
        return response.replaceAll("```json[\\s\\S]*?```", "").trim();
    }

    private List<Map<String, Object>> retrieveRelevantChunks(
            String vectorString, String userId) {
        try {
            String sql = """
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
                You help business owners understand their invoices, track spending,
                and gain financial insights.

                Answer questions based ONLY on the provided financial data context.
                If the data doesn't contain enough information to answer, say so clearly.
                Always mention specific amounts, vendors, and dates when relevant.
                Format currency as NGN amounts with commas (e.g., NGN 220,375).
                Be concise, accurate, and helpful.

                CHART INSTRUCTIONS:
                When the user asks for spending breakdowns, comparisons, trends, or
                visualizations (e.g. "show me", "chart", "breakdown", "by category",
                "by month", "compare"), you MUST include a chart specification.

                Format your response as:
                [Your text answer here]

```json
                {
                  "type": "bar",
                  "title": "Chart Title",
                  "labels": ["Label1", "Label2"],
                  "datasets": [{
                    "label": "Amount (NGN)",
                    "data": [1000, 2000]
                  }]
                }
```

                Chart types: "bar", "line", "pie", "doughnut"
                Only include the chart JSON when it adds value.
                For simple factual questions, just answer in plain text.

                Financial Context:
                """ + context);
        messages.add(systemMessage);

        // Add recent conversation history
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