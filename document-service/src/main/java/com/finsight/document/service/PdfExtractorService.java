package com.finsight.document.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Service
@Slf4j
public class PdfExtractorService {

    public String extractText(String filePath) {
        File pdfFile = new File(filePath);

        if (!pdfFile.exists()) {
            throw new RuntimeException("PDF file not found: " + filePath);
        }

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.info("Extracted {} characters from PDF: {}", text.length(), filePath);
            return text;
        } catch (IOException e) {
            log.error("Failed to extract text from PDF: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to extract PDF text: " + e.getMessage());
        }
    }
}