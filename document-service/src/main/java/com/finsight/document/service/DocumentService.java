package com.finsight.document.service;

import com.finsight.document.dto.DocumentStatusResponse;
import com.finsight.document.dto.DocumentUploadResponse;
import com.finsight.document.dto.ExtractedInvoiceResponse;
import com.finsight.document.dto.SemanticSearchResponse;
import com.finsight.document.entity.Document;
import com.finsight.document.entity.ExtractedInvoice;
import com.finsight.document.repository.DocumentRepository;
import com.finsight.document.repository.ExtractedInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.finsight.document.repository.DocumentEmbeddingRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final ExtractedInvoiceRepository extractedInvoiceRepository;
    private final DocumentProcessingService documentProcessingService;

    private final DocumentEmbeddingRepository documentEmbeddingRepository;
    private final EmbeddingService embeddingService;

    @Value("${finsight.upload.directory}")
    private String uploadDirectory;

    public DocumentUploadResponse uploadDocument(MultipartFile file, UUID userId) {
        // Validate file
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Only PDF files are supported");
        }

        // Save file to disk
        String filePath = saveFileToDisk(file, userId);

        // Create document record
        Document document = Document.builder()
                .userId(userId)
                .filename(filename)
                .filePath(filePath)
                .fileSize(file.getSize())
                .docType("INVOICE")
                .status("UPLOADED")
                .build();

        document = documentRepository.save(document);
        log.info("Document uploaded: {} for user: {}", document.getId(), userId);

        // Trigger async processing
        documentProcessingService.processDocument(document);

        return DocumentUploadResponse.builder()
                .documentId(document.getId())
                .filename(document.getFilename())
                .status(document.getStatus())
                .uploadedAt(document.getUploadedAt())
                .message("Document uploaded successfully. Processing has started.")
                .build();
    }

    public DocumentStatusResponse getDocumentStatus(UUID documentId, UUID userId) {
        Document document = documentRepository
                .findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Document not found: " + documentId));

        return DocumentStatusResponse.builder()
                .documentId(document.getId())
                .filename(document.getFilename())
                .status(document.getStatus())
                .errorMessage(document.getErrorMessage())
                .uploadedAt(document.getUploadedAt())
                .processedAt(document.getProcessedAt())
                .build();
    }

    public ExtractedInvoiceResponse getExtractedInvoice(UUID documentId, UUID userId) {
        documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Document not found: " + documentId));

        ExtractedInvoice invoice = extractedInvoiceRepository
                .findByDocumentId(documentId)
                .orElseThrow(() -> new IllegalStateException(
                        "Invoice not yet extracted for document: " + documentId));

        List<ExtractedInvoiceResponse.LineItemDto> lineItems = invoice.getLineItems()
                .stream()
                .map(item -> ExtractedInvoiceResponse.LineItemDto.builder()
                        .description(item.getDescription())
                        .quantity(item.getQuantity())
                        .unitPrice(item.getUnitPrice())
                        .amount(item.getAmount())
                        .build())
                .collect(Collectors.toList());

        return ExtractedInvoiceResponse.builder()
                .documentId(documentId)
                .vendorName(invoice.getVendorName())
                .invoiceNumber(invoice.getInvoiceNumber())
                .issueDate(invoice.getIssueDate())
                .dueDate(invoice.getDueDate())
                .subtotal(invoice.getSubtotal())
                .tax(invoice.getTax())
                .total(invoice.getTotal())
                .currency(invoice.getCurrency())
                .category(invoice.getCategory())
                .lineItems(lineItems)
                .build();
    }

    private String saveFileToDisk(MultipartFile file, UUID userId) {
        try {
            Path userUploadDir = Paths.get(uploadDirectory, userId.toString());
            Files.createDirectories(userUploadDir);

            String uniqueFilename = UUID.randomUUID() + "_" + file.getOriginalFilename();
            Path filePath = userUploadDir.resolve(uniqueFilename);

            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("File saved to: {}", filePath);

            return filePath.toString();
        } catch (IOException e) {
            log.error("Failed to save file: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to save uploaded file");
        }
    }

    public SemanticSearchResponse semanticSearch(String query, UUID userId) {
        // Embed the query
        float[] queryVector = embeddingService.embedQuery(query);
        String vectorString = embeddingService.vectorToString(queryVector);

        // Search for similar chunks
        List<Object[]> results = documentEmbeddingRepository.findSimilarChunks(
                vectorString, userId.toString(), 5);

        List<SemanticSearchResponse.SearchResult> searchResults = results.stream()
                .map(row -> SemanticSearchResponse.SearchResult.builder()
                        .documentId(row[0].toString())
                        .chunkText(row[1].toString())
                        .similarity(Double.parseDouble(row[2].toString()))
                        .build())
                .collect(Collectors.toList());

        return SemanticSearchResponse.builder()
                .query(query)
                .results(searchResults)
                .build();
    }
}