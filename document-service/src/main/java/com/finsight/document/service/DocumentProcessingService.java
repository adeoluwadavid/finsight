package com.finsight.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.document.client.GroqClient;
import com.finsight.document.entity.Document;
import com.finsight.document.entity.ExtractedInvoice;
import com.finsight.document.entity.InvoiceLineItem;
import com.finsight.document.repository.DocumentRepository;
import com.finsight.document.repository.ExtractedInvoiceRepository;
import com.finsight.document.repository.InvoiceLineItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingService {

    private final DocumentRepository documentRepository;
    private final ExtractedInvoiceRepository extractedInvoiceRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final PdfExtractorService pdfExtractorService;
    private final GroqClient groqClient;
    private final ObjectMapper objectMapper;

    @Async
    @Transactional
    public void processDocument(Document document) {
        log.info("Starting processing for document: {}", document.getId());

        try {
            // Update status to PROCESSING
            document.setStatus("PROCESSING");
            documentRepository.save(document);

            // Step 1: Extract text from PDF
            String pdfText = pdfExtractorService.extractText(document.getFilePath());

            if (pdfText.isBlank()) {
                throw new RuntimeException("PDF appears to be empty or unreadable");
            }

            // Step 2: Send to Groq for structured extraction
            String groqResponse = groqClient.extractInvoiceData(pdfText);
            log.info("Groq extraction complete for document: {}", document.getId());

            // Step 3: Parse and save extracted data
            saveExtractedInvoice(document, groqResponse);

            // Step 4: Mark as completed
            document.setStatus("COMPLETED");
            document.setProcessedAt(LocalDateTime.now());
            documentRepository.save(document);

            log.info("Document processing completed: {}", document.getId());

        } catch (Exception e) {
            log.error("Document processing failed for {}: {}",
                    document.getId(), e.getMessage(), e);
            document.setStatus("FAILED");
            document.setErrorMessage(e.getMessage());
            documentRepository.save(document);
        }
    }

    private void saveExtractedInvoice(Document document, String groqResponse)
            throws Exception {
        // Clean response — Groq sometimes wraps in markdown
        String cleanJson = groqResponse
                .replaceAll("```json", "")
                .replaceAll("```", "")
                .trim();

        JsonNode json = objectMapper.readTree(cleanJson);

        ExtractedInvoice invoice = ExtractedInvoice.builder()
                .document(document)
                .vendorName(getTextOrNull(json, "vendorName"))
                .invoiceNumber(getTextOrNull(json, "invoiceNumber"))
                .issueDate(getDateOrNull(json, "issueDate"))
                .dueDate(getDateOrNull(json, "dueDate"))
                .subtotal(getDecimalOrNull(json, "subtotal"))
                .tax(getDecimalOrNull(json, "tax"))
                .total(getDecimalOrNull(json, "total"))
                .currency(getTextOrNull(json, "currency"))
                .category(getTextOrNull(json, "category"))
                .rawJson(cleanJson)
                .build();

        invoice = extractedInvoiceRepository.save(invoice);

        // Save line items
        List<InvoiceLineItem> lineItems = new ArrayList<>();
        JsonNode lineItemsNode = json.path("lineItems");

        if (lineItemsNode.isArray()) {
            for (JsonNode item : lineItemsNode) {
                InvoiceLineItem lineItem = InvoiceLineItem.builder()
                        .invoice(invoice)
                        .description(getTextOrNull(item, "description"))
                        .quantity(getDecimalOrNull(item, "quantity"))
                        .unitPrice(getDecimalOrNull(item, "unitPrice"))
                        .amount(getDecimalOrNull(item, "amount"))
                        .build();
                lineItems.add(lineItem);
            }
            invoiceLineItemRepository.saveAll(lineItems);
        }

        log.info("Saved extracted invoice with {} line items for document: {}",
                lineItems.size(), document.getId());
    }

    private String getTextOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private LocalDate getDateOrNull(JsonNode node, String field) {
        String value = getTextOrNull(node, field);
        if (value == null || value.equals("null")) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal getDecimalOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNull() || value.isMissingNode()) return null;
        try {
            return new BigDecimal(value.asText());
        } catch (Exception e) {
            return null;
        }
    }
}