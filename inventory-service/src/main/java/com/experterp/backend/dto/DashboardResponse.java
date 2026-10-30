package com.experterp.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Everything the dashboard page shows, for the period {@code from}–{@code to} (both days included). */
public record DashboardResponse(
        LocalDate from,
        LocalDate to,
        int days,
        Kpis kpis,
        Map<String, Long> ordersByStatus,
        // Work waiting right now, whatever the period (Cin7's Sales / Purchases tabs)
        SalesStatus sales,
        PurchaseStatus purchases,
        List<Day> daily,
        List<Seller> topSellers,
        List<Seller> worstSellers,
        List<RecentOrder> recentOrders) {

    public record Kpis(
            long products,
            long warehouses,
            long customers,
            // Σ current stock × product cost price (products without a cost count as 0)
            BigDecimal stockValue,
            // Products whose stock + quantity on order is at or below their minimum (the reorder list, Cin7's rule)
            long productsToReorder,
            // CONFIRMED orders still waiting to ship (fully or partly)
            long ordersToShip,
            long draftOrders,
            // Period figures: orders created in the period, excluding cancelled orders and cancelled quantities
            long ordersInPeriod,
            BigDecimal salesValueInPeriod,
            BigDecimal unitsShippedInPeriod,
            // Issued sales invoices minus payments received
            BigDecimal receivablesOutstanding) {}

    // Invoices "awaiting payment" are issued and not fully paid; "overdue" ones are also past their due date
    public record SalesStatus(long draftOrders, long toShip, long draftInvoices, long awaitingPayment, long overdueInvoices) {}

    // Purchase orders are DRAFT until received, so a draft PO is also the one awaiting delivery
    public record PurchaseStatus(long awaitingDelivery, long draftInvoices, long awaitingPayment, long overdueInvoices) {}

    // Units in/out leave out warehouse transfers, which move stock without changing the total
    public record Day(LocalDate date, long orders, BigDecimal salesValue, BigDecimal unitsIn, BigDecimal unitsOut) {}

    // Ordered in the period on orders that are not cancelled, cancelled quantities left out
    public record Seller(Long productId, String sku, String name, BigDecimal quantity, BigDecimal value) {}

    public record RecentOrder(Long id, String customerName, String status, String fulfilmentStatus,
                              BigDecimal total, LocalDateTime createdAt) {}

    /** A product to reorder: stock + on order is at or below its minimum. */
    public record ReorderLine(Long productId, String sku, String name, String supplierName,
                              BigDecimal stock, BigDecimal onOrder, BigDecimal minStock,
                              // The product's reorder quantity, or else what brings it back to its minimum
                              BigDecimal suggestedQuantity) {}
}
