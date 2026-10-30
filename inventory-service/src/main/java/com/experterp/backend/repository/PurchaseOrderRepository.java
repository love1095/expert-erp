package com.experterp.backend.repository;

import com.experterp.backend.entity.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    boolean existsByOrderNumber(String orderNumber);

    // Optional filters: status and text (q = lower-case LIKE pattern, '' = any) on the supplier, PO number or supplier reference
    @Query("SELECT po FROM PurchaseOrder po WHERE (:status IS NULL OR po.status = :status) " +
           "AND (:q = '' OR LOWER(po.supplierName) LIKE :q OR LOWER(po.orderNumber) LIKE :q " +
           "OR LOWER(COALESCE(po.supplierReference, '')) LIKE :q)")
    Page<PurchaseOrder> search(@Param("status") PurchaseOrder.Status status, @Param("q") String q, Pageable pageable);
}
