package com.experterp.backend.repository;

import com.experterp.backend.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {

    // Issued supplier bill lines of a PO line not yet matched to received stock, oldest first (entered by asOf, if given)
    @Query("SELECT i FROM InvoiceItem i JOIN i.invoice inv WHERE i.purchaseOrderLineId = :lineId " +
           "AND inv.type = com.experterp.backend.entity.Invoice.InvoiceType.PURCHASE " +
           "AND inv.kind = com.experterp.backend.entity.Invoice.Kind.INVOICE " +
           "AND inv.status IN (com.experterp.backend.entity.Invoice.InvoiceStatus.ISSUED, com.experterp.backend.entity.Invoice.InvoiceStatus.PAID) " +
           "AND i.costMatchedQuantity < i.quantity " +
           "AND (CAST(:asOf AS timestamp) IS NULL OR inv.createdAt <= :asOf) " +
           "ORDER BY inv.createdAt, i.id")
    List<InvoiceItem> findUnmatchedBillItems(@Param("lineId") Long purchaseOrderLineId, @Param("asOf") LocalDateTime asOf);
}
