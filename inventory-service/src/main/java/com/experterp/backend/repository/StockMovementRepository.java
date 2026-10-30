package com.experterp.backend.repository;

import com.experterp.backend.entity.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    // Movement types that add stock; every other type subtracts.
    // Must list exactly the MovementType values whose isIn() is true (enforced by StockMovementTypesTest).
    String IN_TYPES = "'PURCHASE_IN','ADJUSTMENT_IN','TRANSFER_IN','SALE_RETURN_IN'";

    Page<StockMovement> findByProductId(Long productId, Pageable pageable);

    Page<StockMovement> findByWarehouseId(Long warehouseId, Pageable pageable);

    Page<StockMovement> findByProductIdAndWarehouseId(Long productId, Long warehouseId, Pageable pageable);

    Page<StockMovement> findByProduct_Category_Id(Long categoryId, Pageable pageable);

    // Stock for one product in one warehouse
    @Query("SELECT COALESCE(SUM(CASE WHEN m.type IN (" + IN_TYPES + ") " +
           "THEN m.quantity ELSE -m.quantity END), 0) " +
           "FROM StockMovement m " +
           "WHERE m.product.id = :productId AND m.warehouse.id = :warehouseId")
    BigDecimal calculateStock(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    // Total stock for one product across all warehouses
    @Query("SELECT COALESCE(SUM(CASE WHEN m.type IN (" + IN_TYPES + ") " +
           "THEN m.quantity ELSE -m.quantity END), 0) " +
           "FROM StockMovement m WHERE m.product.id = :productId")
    BigDecimal calculateTotalStock(@Param("productId") Long productId);

    // Total stock for several products at once: rows of [productId, stock]; products without movements are absent
    @Query("SELECT m.product.id, COALESCE(SUM(CASE WHEN m.type IN (" + IN_TYPES + ") " +
           "THEN m.quantity ELSE -m.quantity END), 0) " +
           "FROM StockMovement m WHERE m.product.id IN :productIds GROUP BY m.product.id")
    List<Object[]> calculateTotalStocks(@Param("productIds") java.util.Collection<Long> productIds);

    // Total stock of every product of the tenant: rows of [productId, stock]
    @Query("SELECT m.product.id, COALESCE(SUM(CASE WHEN m.type IN (" + IN_TYPES + ") " +
           "THEN m.quantity ELSE -m.quantity END), 0) FROM StockMovement m GROUP BY m.product.id")
    List<Object[]> calculateAllTotalStocks();

    // Aggregated stock overview — one row per product+warehouse combo
    // Columns: p.id[0], p.sku[1], p.name[2], p.unit[3], p.min_stock[4],
    //          w.id[5], w.name[6], c.id[7], c.name[8], stock[9]
    @Query(value =
        "SELECT p.id, p.sku, p.name, p.unit, p.min_stock, w.id, w.name, c.id, c.name, " +
        "COALESCE(SUM(CASE WHEN m.type IN (" + IN_TYPES + ") " +
        "    THEN m.quantity ELSE -m.quantity END), 0) AS stock " +
        "FROM stock_movements m " +
        "JOIN products p ON m.product_id = p.id " +
        "JOIN warehouses w ON m.warehouse_id = w.id " +
        "LEFT JOIN categories c ON p.category_id = c.id " +
        "WHERE m.tenant_id = :tenantId AND p.tenant_id = :tenantId AND w.tenant_id = :tenantId " +
        "GROUP BY p.id, p.sku, p.name, p.unit, p.min_stock, w.id, w.name, c.id, c.name " +
        "ORDER BY p.name, w.name",
        nativeQuery = true)
    List<Object[]> getStockOverviewRaw(@Param("tenantId") Long tenantId);

    // Optional filters: category, text (q = lower-case LIKE pattern, '' = any) on SKU or name, active products only,
    // and only rows whose stock is not zero (Cin7's "Only active" / "Show only non-zero quantities").
    // Columns as getStockOverviewRaw, plus p.cost_price[10]
    String OVERVIEW_FILTER =
        "WHERE m.tenant_id = :tenantId AND p.tenant_id = :tenantId AND w.tenant_id = :tenantId " +
        "  AND (CAST(:categoryId AS bigint) IS NULL OR p.category_id = :categoryId) " +
        "  AND (:q = '' OR LOWER(p.sku) LIKE :q OR LOWER(p.name) LIKE :q) " +
        "  AND (:activeOnly = false OR p.status = 'ACTIVE') ";
    String OVERVIEW_STOCK = "COALESCE(SUM(CASE WHEN m.type IN (" + IN_TYPES + ") THEN m.quantity ELSE -m.quantity END), 0)";

    @Query(value =
        "SELECT p.id, p.sku, p.name, p.unit, p.min_stock, w.id, w.name, c.id, c.name, " + OVERVIEW_STOCK + " AS stock, p.cost_price " +
        "FROM stock_movements m " +
        "JOIN products p ON m.product_id = p.id " +
        "JOIN warehouses w ON m.warehouse_id = w.id " +
        "LEFT JOIN categories c ON p.category_id = c.id " +
        OVERVIEW_FILTER +
        "GROUP BY p.id, p.sku, p.name, p.unit, p.min_stock, w.id, w.name, c.id, c.name, p.cost_price " +
        "HAVING (:nonZero = false OR " + OVERVIEW_STOCK + " <> 0) " +
        "ORDER BY p.name, w.name",
        countQuery =
        "SELECT COUNT(*) FROM (" +
        "  SELECT p.id FROM stock_movements m " +
        "  JOIN products p ON m.product_id = p.id " +
        "  JOIN warehouses w ON m.warehouse_id = w.id " +
        OVERVIEW_FILTER +
        "  GROUP BY p.id, w.id" +
        "  HAVING (:nonZero = false OR " + OVERVIEW_STOCK + " <> 0)" +
        ") sub",
        nativeQuery = true)
    Page<Object[]> getStockOverviewPaged(@Param("tenantId") Long tenantId, @Param("categoryId") Long categoryId,
                                         @Param("q") String q, @Param("activeOnly") boolean activeOnly,
                                         @Param("nonZero") boolean nonZero, Pageable pageable);
}
