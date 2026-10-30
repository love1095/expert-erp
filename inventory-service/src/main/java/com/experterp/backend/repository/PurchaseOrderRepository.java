package com.experterp.backend.repository;

import com.experterp.backend.entity.PurchaseOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    boolean existsByOrderNumber(String orderNumber);

    // Draft / ordered POs with a line for the product (on order)
    @Query("SELECT DISTINCT po FROM PurchaseOrder po JOIN po.lines l WHERE l.product.id = :productId " +
           "AND po.status IN (com.experterp.backend.entity.PurchaseOrder.Status.DRAFT, com.experterp.backend.entity.PurchaseOrder.Status.ORDERED) " +
           "ORDER BY po.id")
    java.util.List<PurchaseOrder> openOrdersForProduct(@Param("productId") Long productId);

    // Serialises receiving / closing on the same order
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT po FROM PurchaseOrder po WHERE po.id = :id")
    java.util.Optional<PurchaseOrder> findByIdForUpdate(@Param("id") Long id);

    // Optional filters: status and text (q = lower-case LIKE pattern, '' = any) on the supplier, PO number or supplier reference
    @Query("SELECT po FROM PurchaseOrder po WHERE (:status IS NULL OR po.status = :status) " +
           "AND (:q = '' OR LOWER(po.supplierName) LIKE :q OR LOWER(po.orderNumber) LIKE :q " +
           "OR LOWER(COALESCE(po.supplierReference, '')) LIKE :q)")
    Page<PurchaseOrder> search(@Param("status") PurchaseOrder.Status status, @Param("q") String q, Pageable pageable);

    // On order per product + warehouse (Cin7's "On order" / "Next delivery"): what ordered POs still have to bring in,
    // and the earliest required-by date among them. Rows: product_id[0], warehouse_id[1], qty[2], next_delivery[3]
    @Query(value =
        "SELECT l.product_id, po.warehouse_id, SUM(l.quantity - l.received_quantity - l.closed_short_quantity), MIN(po.required_by) " +
        "  FROM purchase_order_lines l JOIN purchase_orders po ON po.id = l.purchase_order_id " +
        " WHERE po.tenant_id = :tenantId AND po.status = 'ORDERED' AND l.product_id IN (:productIds) " +
        "   AND l.quantity - l.received_quantity - l.closed_short_quantity > 0 " +
        " GROUP BY l.product_id, po.warehouse_id",
        nativeQuery = true)
    java.util.List<Object[]> onOrderByProductAndWarehouse(@Param("tenantId") Long tenantId,
                                                         @Param("productIds") java.util.Collection<Long> productIds);
}
