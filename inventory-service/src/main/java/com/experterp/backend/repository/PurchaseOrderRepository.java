package com.experterp.backend.repository;

import com.experterp.backend.entity.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    // Optional filters: status and text (q = lower-case LIKE pattern, '' = any) on the supplier or the PO number (PO-12)
    @Query("SELECT po FROM PurchaseOrder po WHERE (:status IS NULL OR po.status = :status) " +
           "AND (:q = '' OR LOWER(po.supplierName) LIKE :q OR CONCAT('%po-', CAST(po.id AS string), '%') LIKE :q)")
    Page<PurchaseOrder> search(@Param("status") PurchaseOrder.Status status, @Param("q") String q, Pageable pageable);
}
