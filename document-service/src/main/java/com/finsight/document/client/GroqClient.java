package com.finsight.document.client;

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

@Component
@Slf4j
public class GroqClient {

    @Value("${finsight.groq.api-key}")
    private String apiKey;

    @Value("${finsight.groq.base-url}")
    private String baseUrl;

    @Value("${finsight.groq.model}")
    private String model;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GroqClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public String extractInvoiceData(String pdfText) {
        String prompt = buildExtractionPrompt(pdfText);
        String requestBody = buildRequestBody(prompt);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Groq API error: status={}, body={}",
                        response.statusCode(), response.body());
                throw new RuntimeException("Groq API returned status: "
                        + response.statusCode());
            }

            JsonNode responseJson = objectMapper.readTree(response.body());
            return responseJson
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

        } catch (Exception e) {
            log.error("Error calling Groq API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call Groq API: " + e.getMessage());
        }
    }

    private String buildExtractionPrompt(String pdfText) {
        return """
                You are a financial document parser. Extract invoice data from the following text and return ONLY a valid JSON object with no additional text, explanation, or markdown.
                
                Required JSON structure:
                {
                  "vendorName": "string or null",
                  "invoiceNumber": "string or null",
                  "issueDate": "YYYY-MM-DD or null",
                  "dueDate": "YYYY-MM-DD or null",
                  "subtotal": number or null,
                  "tax": number or null,
                  "total": number or null,
                  "currency": "NGN or USD or GBP or EUR or null",
                  "category": "one of: Technology, Marketing, Logistics, Office Supplies, Utilities, Professional Services, Food & Beverage, Other",
                  "lineItems": [
                    {
                      "description": "string",
                      "quantity": number,
                      "unitPrice": number,
                      "amount": number
                    }
                  ]
                }
                
                Document text:
                """ + pdfText;
    }

    private String buildRequestBody(String prompt) {
        try {
            var requestMap = new java.util.HashMap<String, Object>();
            requestMap.put("model", model);
            requestMap.put("temperature", 0.1);
            requestMap.put("max_tokens", 2000);

            var message = new java.util.HashMap<String, String>();
            message.put("role", "user");
            message.put("content", prompt);

            requestMap.put("messages", java.util.List.of(message));

            return objectMapper.writeValueAsString(requestMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build request body", e);
        }
    }
}