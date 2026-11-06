package com.experterp.backend.service;

import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * FIFO costing (docs/business-rules.md section 2, phase E5). Every stock-in opens a cost layer, every stock-out takes
 * from the oldest layers first and records what it took (the COGS). A transfer moves layers to the other warehouse at
 * their cost and date. A supplier bill priced differently from the PO corrects the receipt's layer through cost
 * adjustments: the part still in stock changes the stock value, the part already shipped changes those shipments' COGS.
 * Nothing already recorded is edited, so layers can be rebuilt by replaying the movements in order.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostingService {

    static final int COST_SCALE = 6;
    static final int AMOUNT_SCALE = 4;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final CostLayerRepository layerRepository;
    private final CostConsumptionRepository consumptionRepository;
    private final CostAdjustmentRepository adjustmentRepository;
    private final InvoiceItemRepository invoiceItemRepository;
    private final StockMovementRepository movementRepository;
    private final EntityManager em;

    /** The cost side of a movement just saved. Must be called for every movement, in the order they happen. */
    @Transactional
    public void record(StockMovement m) {
        record(m, null);
    }

    /** As {@link #record(StockMovement)}; when rebuilding, only bills entered by {@code billsAsOf} are matched. */
    @Transactional
    public void record(StockMovement m, LocalDateTime billsAsOf) {
        switch (m.getType()) {
            case TRANSFER_IN -> transferIn(m);
            case SALE_RETURN_IN -> open(m, returnCost(m), null);
            case PURCHASE_IN -> {
                open(m, inCost(m), m.getSourceLineId());
                matchBills(m.getSourceLineId(), billsAsOf);
            }
            case ADJUSTMENT_IN -> open(m, inCost(m), null);
            // Goods back to the supplier leave at the cost they came in at: that PO line's layers first (H2)
            case PURCHASE_RETURN_OUT -> consume(m, m.getSourceLineId());
            default -> consume(m, null);
        }
    }

    // ---- stock in ----

    // Receipt = PO line cost after discount; opening balance / adjustment = the cost given, else the product's cost price
    private static BigDecimal inCost(StockMovement m) {
        if (m.getUnitCost() != null) return m.getUnitCost();
        BigDecimal cost = m.getProduct().getCostPrice();
        return cost != null ? cost : BigDecimal.ZERO;
    }

    private CostLayer open(StockMovement m, BigDecimal unitCost, Long purchaseOrderLineId) {
        CostLayer layer = newLayer(m, m.getQuantity(), unitCost, m.getCreatedAt());
        layer.setPurchaseOrderLineId(purchaseOrderLineId);
        if (m.getUnitCost() == null) m.setUnitCost(unitCost.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
        return layerRepository.save(layer);
    }

    private CostLayer newLayer(StockMovement m, BigDecimal qty, BigDecimal unitCost, LocalDateTime receivedAt) {
        CostLayer layer = new CostLayer();
        layer.setProductId(m.getProduct().getId());
        layer.setWarehouseId(m.getWarehouse().getId());
        layer.setMovementId(m.getId());
        layer.setReceivedAt(receivedAt);
        layer.setQuantity(qty);
        layer.setRemaining(qty);
        BigDecimal cost = unitCost.setScale(COST_SCALE, RoundingMode.HALF_UP);
        layer.setUnitCost(cost);
        layer.setOriginalUnitCost(cost);
        return layer;
    }

    // A restocked return comes back at the cost it left at: what the line's shipments cost per unit
    private BigDecimal returnCost(StockMovement m) {
        if (m.getSourceLineId() != null) {
            Object[] r = (Object[]) em.createNativeQuery(
                    "SELECT COALESCE(SUM(x.cost), 0), COALESCE(SUM(x.qty), 0) FROM (" +
                    "  SELECT c.quantity * c.unit_cost AS cost, c.quantity AS qty FROM cost_consumptions c " +
                    "    JOIN stock_movements m ON m.id = c.movement_id WHERE m.type = 'SALE_OUT' AND m.source_line_id = :line" +
                    "  UNION ALL" +
                    "  SELECT a.amount, 0 FROM cost_adjustments a " +
                    "    JOIN stock_movements m ON m.id = a.movement_id WHERE m.type = 'SALE_OUT' AND m.source_line_id = :line" +
                    ") x").setParameter("line", m.getSourceLineId()).getSingleResult();
            BigDecimal cost = new BigDecimal(r[0].toString()), qty = new BigDecimal(r[1].toString());
            if (qty.signum() > 0) return cost.divide(qty, COST_SCALE, RoundingMode.HALF_UP);
        }
        return inCost(m);
    }

    // The layers the transfer took out of the other warehouse arrive here at their own cost and date
    private void transferIn(StockMovement in) {
        StockMovement out = movementRepository.findFirstByReferenceIdAndProductIdAndTypeOrderByIdDesc(
                in.getReferenceId(), in.getProduct().getId(), MovementType.TRANSFER_OUT).orElse(null);
        List<CostConsumption> taken = out == null ? List.of() : consumptionRepository.findByMovementIdOrderByIdAsc(out.getId())
                .stream().filter(c -> c.getChildLayerId() == null).toList();
        if (taken.isEmpty()) {
            open(in, inCost(in), null);
            return;
        }
        BigDecimal value = BigDecimal.ZERO;
        for (CostConsumption c : taken) {
            CostLayer source = layerRepository.findById(c.getLayerId()).orElseThrow();
            CostLayer child = newLayer(in, c.getQuantity(), c.getUnitCost(), source.getReceivedAt());
            child.setSourceLayerId(source.getId());
            child = layerRepository.save(child);
            c.setChildLayerId(child.getId());
            value = value.add(c.getQuantity().multiply(c.getUnitCost()));
        }
        if (in.getUnitCost() == null) in.setUnitCost(value.divide(in.getQuantity(), AMOUNT_SCALE, RoundingMode.HALF_UP));
    }

    // ---- stock out ----

    private void consume(StockMovement m, Long preferPurchaseOrderLineId) {
        BigDecimal need = m.getQuantity();
        List<CostLayer> open = new ArrayList<>(layerRepository.findOpen(m.getProduct().getId(), m.getWarehouse().getId()));
        if (preferPurchaseOrderLineId != null) {
            // Stable: the line's own layers (oldest first), then the rest in FIFO order
            open.sort(Comparator.comparing(l -> !preferPurchaseOrderLineId.equals(l.getPurchaseOrderLineId())));
        }
        for (CostLayer layer : open) {
            if (need.signum() <= 0) break;
            BigDecimal take = need.min(layer.getRemaining());
            layer.setRemaining(layer.getRemaining().subtract(take));
            consumptionRepository.save(consumption(layer, m, take));
            need = need.subtract(take);
        }
        if (need.signum() > 0) {
            // Only when the history has gone below zero (stock checks didn't exist then): costed at the cost price
            log.warn("FIFO: {} of product {} left warehouse {} with no cost layer (movement {})",
                    need.toPlainString(), m.getProduct().getId(), m.getWarehouse().getId(), m.getId());
            CostLayer shortfall = newLayer(m, need, inCost(m), m.getCreatedAt());
            shortfall.setRemaining(BigDecimal.ZERO);
            shortfall = layerRepository.save(shortfall);
            consumptionRepository.save(consumption(shortfall, m, need));
        }
    }

    private static CostConsumption consumption(CostLayer layer, StockMovement m, BigDecimal qty) {
        CostConsumption c = new CostConsumption();
        c.setLayerId(layer.getId());
        c.setMovementId(m.getId());
        c.setQuantity(qty);
        c.setUnitCost(layer.getUnitCost());
        return c;
    }

    // ---- supplier bills ----

    /** A supplier bill was issued: its lines price the stock received on their PO lines (oldest receipts first). */
    @Transactional
    public void billIssued(Invoice bill) {
        billIssued(bill, null);
    }

    /** As {@link #billIssued(Invoice)}; when rebuilding, only bills entered by {@code asOf} (this one's time) count. */
    @Transactional
    public void billIssued(Invoice bill, LocalDateTime asOf) {
        bill.getItems().stream().map(InvoiceItem::getPurchaseOrderLineId).filter(Objects::nonNull).distinct()
                .forEach(lineId -> matchBills(lineId, asOf));
    }

    /**
     * An issued bill was cancelled: its corrections are taken back by opposite adjustments, and the units it priced
     * go to the next bill of the line, if any.
     */
    @Transactional
    public void billCancelled(Invoice bill) {
        Set<Long> lines = new LinkedHashSet<>();
        for (InvoiceItem item : bill.getItems()) {
            if (item.getPurchaseOrderLineId() == null) continue;
            Map<Long, BigDecimal> unitsByLayer = new LinkedHashMap<>();
            adjustmentRepository.findMatches(item.getId())
                    .forEach(a -> unitsByLayer.merge(a.getLayerId(), a.getBilledQuantity(), BigDecimal::add));
            for (Map.Entry<Long, BigDecimal> e : unitsByLayer.entrySet()) {
                if (e.getValue().signum() <= 0) continue;
                CostLayer layer = layerRepository.findById(e.getKey()).orElseThrow();
                BigDecimal amount = correction(item, layer, e.getValue()).negate();
                adjust(layer, amount, item.getId(), e.getValue().negate(), "Bill " + bill.getInvoiceNumber() + " cancelled", LocalDateTime.now());
                layer.setBilledQuantity(layer.getBilledQuantity().subtract(e.getValue()).max(BigDecimal.ZERO));
            }
            item.setCostMatchedQuantity(BigDecimal.ZERO);
            lines.add(item.getPurchaseOrderLineId());
        }
        lines.forEach(lineId -> matchBills(lineId, null));
    }

    // Issued bill units of a PO line not yet matched take the line's received units not yet billed, both oldest first
    void matchBills(Long purchaseOrderLineId, LocalDateTime asOf) {
        if (purchaseOrderLineId == null) return;
        List<InvoiceItem> items = invoiceItemRepository.findUnmatchedBillItems(purchaseOrderLineId, asOf);
        if (items.isEmpty()) return;
        // When rebuilding, the correction is dated when it happened then (the bill or the receipt), not now: reports read it
        LocalDateTime at = asOf != null ? asOf : LocalDateTime.now();
        Iterator<CostLayer> layers = layerRepository.findUnbilled(purchaseOrderLineId).iterator();
        CostLayer layer = layers.hasNext() ? layers.next() : null;
        for (InvoiceItem item : items) {
            while (layer != null && item.getCostMatchedQuantity().compareTo(item.getQuantity()) < 0) {
                BigDecimal units = item.getQuantity().subtract(item.getCostMatchedQuantity())
                        .min(layer.getQuantity().subtract(layer.getBilledQuantity()));
                BigDecimal amount = correction(item, layer, units);
                adjust(layer, amount, item.getId(), units, "Bill " + item.getInvoice().getInvoiceNumber() + " at "
                        + money(netUnitPrice(item)) + ", received at " + money(layer.getOriginalUnitCost()), at);
                layer.setBilledQuantity(layer.getBilledQuantity().add(units));
                item.setCostMatchedQuantity(item.getCostMatchedQuantity().add(units));
                if (layer.getBilledQuantity().compareTo(layer.getQuantity()) >= 0) layer = layers.hasNext() ? layers.next() : null;
            }
            if (layer == null) break;
        }
    }

    // What the bill changes on these units: (bill price after discount − the cost they came in at) × units
    private static BigDecimal correction(InvoiceItem item, CostLayer layer, BigDecimal units) {
        return netUnitPrice(item).subtract(layer.getOriginalUnitCost()).multiply(units).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    static BigDecimal netUnitPrice(InvoiceItem item) {
        BigDecimal discount = item.getDiscountPercent() == null ? BigDecimal.ZERO : item.getDiscountPercent();
        return item.getUnitPrice().multiply(HUNDRED.subtract(discount)).divide(HUNDRED, COST_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Spreads a change of value over a layer: each stock-out that took from it gets its share (its quantity / the
     * layer's), passed on to the layer it opened when it was a transfer; the rest is the stock still in the layer.
     */
    private void adjust(CostLayer layer, BigDecimal amount, Long invoiceItemId, BigDecimal billedUnits, String reason, LocalDateTime at) {
        BigDecimal passed = BigDecimal.ZERO;
        if (amount.signum() != 0) {
            for (CostConsumption c : consumptionRepository.findByLayerIdOrderByIdAsc(layer.getId())) {
                BigDecimal part = amount.multiply(c.getQuantity()).divide(layer.getQuantity(), AMOUNT_SCALE, RoundingMode.HALF_UP);
                if (part.signum() == 0) continue;
                if (c.getChildLayerId() != null) {
                    adjust(layerRepository.findById(c.getChildLayerId()).orElseThrow(), part, invoiceItemId, null, reason, at);
                } else {
                    adjustmentRepository.save(adjustment(layer, c.getMovementId(), invoiceItemId, null, part, reason, at));
                }
                passed = passed.add(part);
            }
            layer.setUnitCost(layer.getUnitCost().add(amount.divide(layer.getQuantity(), COST_SCALE, RoundingMode.HALF_UP)));
        }
        BigDecimal inStock = amount.subtract(passed);
        if (billedUnits != null || inStock.signum() != 0) {
            adjustmentRepository.save(adjustment(layer, null, invoiceItemId, billedUnits, inStock, reason, at));
        }
    }

    private static CostAdjustment adjustment(CostLayer layer, Long movementId, Long invoiceItemId, BigDecimal billedUnits,
                                             BigDecimal amount, String reason, LocalDateTime at) {
        CostAdjustment a = new CostAdjustment();
        a.setCreatedAt(at);
        a.setLayerId(layer.getId());
        a.setMovementId(movementId);
        a.setInvoiceItemId(invoiceItemId);
        a.setBilledQuantity(billedUnits);
        a.setAmount(amount);
        a.setReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        return a;
    }

    private static String money(BigDecimal v) {
        return "£" + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ---- reading ----

    /** Cost of goods per sale order line: its shipments (with later bill corrections) less what came back to stock. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> costOfGoodsBySaleOrderLine(Collection<Long> lineIds) {
        if (lineIds.isEmpty()) return Map.of();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT x.line_id, SUM(x.cost) FROM (" +
                "  SELECT m.source_line_id AS line_id, c.quantity * c.unit_cost AS cost FROM cost_consumptions c" +
                "    JOIN stock_movements m ON m.id = c.movement_id WHERE m.type = 'SALE_OUT' AND m.source_line_id IN (:ids)" +
                "  UNION ALL" +
                "  SELECT m.source_line_id, a.amount FROM cost_adjustments a" +
                "    JOIN stock_movements m ON m.id = a.movement_id WHERE m.type = 'SALE_OUT' AND m.source_line_id IN (:ids)" +
                "  UNION ALL" +
                "  SELECT m.source_line_id, -(l.quantity * l.original_unit_cost) FROM cost_layers l" +
                "    JOIN stock_movements m ON m.id = l.movement_id WHERE m.type = 'SALE_RETURN_IN' AND m.source_line_id IN (:ids)" +
                ") x GROUP BY x.line_id").setParameter("ids", lineIds).getResultList();
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Object[] r : rows) {
            result.put(((Number) r[0]).longValue(), new BigDecimal(r[1].toString()).setScale(2, RoundingMode.HALF_UP));
        }
        return result;
    }

    /** Cost of goods of one stock-out (a shipment): what it took from the layers plus later corrections. */
    @Transactional(readOnly = true)
    public BigDecimal costOfMovement(Long movementId) {
        Object r = em.createNativeQuery(
                "SELECT COALESCE((SELECT SUM(c.quantity * c.unit_cost) FROM cost_consumptions c WHERE c.movement_id = :m), 0)" +
                "     + COALESCE((SELECT SUM(a.amount) FROM cost_adjustments a WHERE a.movement_id = :m), 0)")
                .setParameter("m", movementId).getSingleResult();
        return new BigDecimal(r.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    /** Total change a bill made to stock cost and COGS (zero when its prices are the PO's). */
    @Transactional(readOnly = true)
    public BigDecimal billCostAdjustment(Invoice bill) {
        List<Long> ids = bill.getItems().stream().filter(i -> i.getPurchaseOrderLineId() != null && i.getId() != null)
                .map(InvoiceItem::getId).toList();
        return ids.isEmpty() ? BigDecimal.ZERO.setScale(2) : adjustmentRepository.totalForItems(ids).setScale(2, RoundingMode.HALF_UP);
    }
}
