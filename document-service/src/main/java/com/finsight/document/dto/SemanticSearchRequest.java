package com.finsight.document.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SemanticSearchRequest {

    @NotBlank(message = "Query is required")
    private String query;

    private int limit = 5;
}