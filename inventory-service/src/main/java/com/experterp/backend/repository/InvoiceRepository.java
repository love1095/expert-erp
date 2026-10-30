package com.experterp.backend.repository;

import com.experterp.backend.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Page<Invoice> findByType(Invoice.InvoiceType type, Pageable pageable);

    // Optional filters: type, status and text (q = lower-case LIKE pattern, '' = any) on the number or party
    @Query("SELECT i FROM Invoice i WHERE (:type IS NULL OR i.type = :type) AND (:status IS NULL OR i.status = :status) " +
           "AND (:q = '' OR LOWER(COALESCE(i.invoiceNumber, '')) LIKE :q OR LOWER(COALESCE(i.partyName, '')) LIKE :q)")
    Page<Invoice> search(@Param("type") Invoice.InvoiceType type, @Param("status") Invoice.InvoiceStatus status,
                         @Param("q") String q, Pageable pageable);

    boolean existsByInvoiceNumber(String invoiceNumber);

    java.util.List<Invoice> findBySaleOrderIdOrderByCreatedAtAscIdAsc(Long saleOrderId);

    // Invoices and credit notes of a page of orders (the sales list's Documents column)
    java.util.List<Invoice> findBySaleOrderIdInOrderByCreatedAtAscIdAsc(java.util.Collection<Long> saleOrderIds);
}
