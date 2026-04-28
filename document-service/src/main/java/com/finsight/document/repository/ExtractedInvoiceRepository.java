package com.finsight.document.repository;

import com.finsight.document.entity.ExtractedInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExtractedInvoiceRepository extends JpaRepository<ExtractedInvoice, UUID> {
    Optional<ExtractedInvoice> findByDocumentId(UUID documentId);
}