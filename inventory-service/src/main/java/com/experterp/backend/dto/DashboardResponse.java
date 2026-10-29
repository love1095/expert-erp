package com.experterp.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Everything the dashboard page shows, for the last {@code days} days (today included). */
public record DashboardResponse(
        int days,
        Kpis kpis,
        Map<String, Long> ordersByStatus,
        List<Day> daily,
        List<TopProduct> topProducts,
        List<LowStock> lowStock,
        List<RecentOrder> recentOrders) {

    public record Kpis(
            long products,
            long warehouses,
            long customers,
            // Σ current stock × product cost price (products without a cost count as 0)
            BigDecimal stockValue,
            // Products whose stock across all warehouses is below their minimum
            long lowStockProducts,
            // CONFIRMED orders still waiting to ship (fully or partly)
            long ordersToShip,
            long draftOrders,
            // Period figures: orders created in the period, excluding cancelled orders and cancelled quantities
            long ordersInPeriod,
            BigDecimal salesValueInPeriod,
            BigDecimal unitsShippedInPeriod,
            // Issued sales invoices minus payments received
            BigDecimal receivablesOutstanding) {}

    // Units in/out leave out warehouse transfers, which move stock without changing the total
    public record Day(LocalDate date, long orders, BigDecimal salesValue, BigDecimal unitsIn, BigDecimal unitsOut) {}

    public record TopProduct(Long productId, String sku, String name, BigDecimal unitsShipped) {}

    public record LowStock(Long productId, String sku, String name, BigDecimal stock, BigDecimal minStock) {}

    public record RecentOrder(Long id, String customerName, String status, String fulfilmentStatus,
                              BigDecimal total, LocalDateTime createdAt) {}
}
