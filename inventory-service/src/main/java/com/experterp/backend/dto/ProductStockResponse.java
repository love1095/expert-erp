package com.experterp.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A product's stock page (Cin7's Stock tab): per warehouse, and what is on open purchase orders. */
public record ProductStockResponse(List<Warehouse> warehouses, List<OpenOrder> onOrder,
                                   BigDecimal onHand, BigDecimal reserved, BigDecimal available, BigDecimal onOrderTotal,
                                   List<CostLayer> costLayers, BigDecimal stockValue) {

    // A FIFO cost layer still holding stock (oldest first per warehouse): where it came from, what is left, its unit cost
    // now and when it came in (they differ after a supplier bill at another price than the PO)
    public record CostLayer(Long id, Long warehouseId, String warehouseName, java.time.LocalDateTime receivedAt, String source,
                            BigDecimal quantity, BigDecimal remaining, BigDecimal unitCost, BigDecimal originalUnitCost,
                            BigDecimal value) {}

    // available = on hand − reserved for sale orders (can be < 0: backordered)
    public record Warehouse(Long warehouseId, String warehouseName, BigDecimal onHand, BigDecimal reserved, BigDecimal available) {}

    // An open purchase order line for the product: still to come
    public record OpenOrder(Long purchaseOrderId, String orderNumber, String supplierName, String status,
                            BigDecimal outstanding, LocalDate requiredBy) {}
}
