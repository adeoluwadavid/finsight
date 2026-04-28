package com.finsight.document.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentStatusResponse {
    private UUID documentId;
    private String filename;
    private String status;
    private String errorMessage;
    private LocalDateTime uploadedAt;
    private LocalDateTime processedAt;
}