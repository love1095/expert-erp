package com.experterp.backend.repository;

import com.experterp.backend.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Page<Invoice> findByType(Invoice.InvoiceType type, Pageable pageable);
}
