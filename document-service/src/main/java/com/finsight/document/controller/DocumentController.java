package com.finsight.document.controller;

import com.finsight.document.dto.*;
import com.finsight.document.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<DocumentUploadResponse>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestHeader("X-User-Id") String userId) {

        DocumentUploadResponse response = documentService.uploadDocument(
                file, UUID.fromString(userId));

        return ResponseEntity
                .status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success("Document uploaded successfully", response));
    }

    @GetMapping("/{documentId}/status")
    public ResponseEntity<ApiResponse<DocumentStatusResponse>> getStatus(
            @PathVariable UUID documentId,
            @RequestHeader("X-User-Id") String userId) {

        DocumentStatusResponse response = documentService.getDocumentStatus(
                documentId, UUID.fromString(userId));

        return ResponseEntity.ok(ApiResponse.success("Document status retrieved", response));
    }

    @GetMapping("/{documentId}/extracted")
    public ResponseEntity<ApiResponse<ExtractedInvoiceResponse>> getExtracted(
            @PathVariable UUID documentId,
            @RequestHeader("X-User-Id") String userId) {

        ExtractedInvoiceResponse response = documentService.getExtractedInvoice(
                documentId, UUID.fromString(userId));

        return ResponseEntity.ok(ApiResponse.success("Extracted invoice retrieved", response));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("Document service is running", "OK"));
    }

    @PostMapping("/search")
    public ResponseEntity<ApiResponse<SemanticSearchResponse>> search(
            @Valid @RequestBody SemanticSearchRequest request,
            @RequestHeader("X-User-Id") String userId) {

        SemanticSearchResponse response = documentService.semanticSearch(
                request.getQuery(), UUID.fromString(userId));

        return ResponseEntity.ok(ApiResponse.success("Search completed", response));
    }
}