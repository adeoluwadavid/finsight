package com.finsight.conversation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class EmbeddingClient {

    @Value("${finsight.jina.api-key}")
    private String apiKey;

    @Value("${finsight.jina.base-url}")
    private String baseUrl;

    @Value("${finsight.jina.model}")
    private String model;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public EmbeddingClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public float[] embed(String text) {
        try {
            Map<String, Object> requestBody = Map.of(
                    "model", model,
                    "input", List.of(text)
            );

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embeddings"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Jina API returned status: "
                        + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            JsonNode embeddingNode = responseJson.path("data").path(0).path("embedding");

            float[] embedding = new float[embeddingNode.size()];
            for (int i = 0; i < embeddingNode.size(); i++) {
                embedding[i] = (float) embeddingNode.get(i).asDouble();
            }

            log.info("Generated query embedding of size {}", embedding.length);
            return embedding;

        } catch (Exception e) {
            log.error("Error calling Jina API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to generate embedding: " + e.getMessage());
        }
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
}