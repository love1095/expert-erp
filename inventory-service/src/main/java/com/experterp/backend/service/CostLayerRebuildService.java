package com.experterp.backend.service;

import com.experterp.backend.entity.Invoice;
import com.experterp.backend.entity.StockMovement;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.InvoiceRepository;
import com.experterp.backend.repository.StockMovementRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Builds an organisation's FIFO cost layers again from its history (docs/business-rules.md section 2: "layers can be
 * rebuilt by replaying the movements in order"): every movement in the order it happened, with the supplier bills
 * applied at the time they were entered. Run once for data that existed before costing, and on request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostLayerRebuildService {

    private static final int BATCH = 200;

    private final StockMovementRepository movementRepository;
    private final InvoiceRepository invoiceRepository;
    private final CostingService costingService;
    private final EntityManager em;

    public record Result(int movements, int bills, int layers, BigDecimal stockValue) {}

    @Transactional
    public Result rebuild() {
        Long tenantId = TenantContext.get();
        if (tenantId == null) throw new IllegalStateException("No organisation");
        em.flush();
        em.clear();
        for (String sql : List.of(
                "DELETE FROM cost_adjustments WHERE tenant_id = :t",
                "DELETE FROM cost_consumptions WHERE tenant_id = :t",
                "UPDATE cost_layers SET source_layer_id = NULL WHERE tenant_id = :t AND source_layer_id IS NOT NULL",
                "DELETE FROM cost_layers WHERE tenant_id = :t",
                "UPDATE invoice_items SET cost_matched_quantity = 0 WHERE cost_matched_quantity <> 0 " +
                "   AND invoice_id IN (SELECT id FROM invoices WHERE tenant_id = :t)")) {
            em.createNativeQuery(sql).setParameter("t", tenantId).executeUpdate();
        }

        // Issued supplier bills of purchase orders, in the order they were entered
        @SuppressWarnings("unchecked")
        List<Object[]> bills = em.createNativeQuery(
                "SELECT id, created_at FROM invoices WHERE tenant_id = :t AND type = 'PURCHASE' AND kind = 'INVOICE' " +
                " AND purchase_order_id IS NOT NULL AND status IN ('ISSUED', 'PAID') ORDER BY created_at, id")
                .setParameter("t", tenantId).getResultList();
        List<Long> movementIds = movementRepository.findAllIdsInOrder();

        int b = 0, done = 0;
        for (Long id : movementIds) {
            StockMovement m = movementRepository.findById(id).orElseThrow();
            while (b < bills.size() && toTime(bills.get(b)[1]).isBefore(m.getCreatedAt())) applyBill(bills.get(b++));
            costingService.record(m, m.getCreatedAt());
            if (++done % BATCH == 0) {
                em.flush();
                em.clear();
            }
        }
        while (b < bills.size()) applyBill(bills.get(b++));
        em.flush();
        em.clear();

        Number layers = (Number) em.createNativeQuery("SELECT COUNT(*) FROM cost_layers WHERE tenant_id = :t")
                .setParameter("t", tenantId).getSingleResult();
        Object value = em.createNativeQuery("SELECT COALESCE(SUM(remaining * unit_cost), 0) FROM cost_layers WHERE tenant_id = :t")
                .setParameter("t", tenantId).getSingleResult();
        Result r = new Result(movementIds.size(), bills.size(), layers.intValue(),
                new BigDecimal(value.toString()).setScale(2, java.math.RoundingMode.HALF_UP));
        log.info("FIFO cost layers rebuilt for organisation {}: {}", tenantId, r);
        return r;
    }

    private void applyBill(Object[] row) {
        Invoice bill = invoiceRepository.findById(((Number) row[0]).longValue()).orElseThrow();
        costingService.billIssued(bill, bill.getCreatedAt());
    }

    private static java.time.LocalDateTime toTime(Object o) {
        return o instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (java.time.LocalDateTime) o;
    }

    /** Organisations with stock movements but no cost layer yet (their history predates FIFO costing). */
    @SuppressWarnings("unchecked")
    public List<Long> tenantsToBuild() {
        return ((List<Number>) em.createNativeQuery(
                "SELECT DISTINCT m.tenant_id FROM stock_movements m WHERE m.tenant_id IS NOT NULL " +
                " AND NOT EXISTS (SELECT 1 FROM cost_layers l WHERE l.tenant_id = m.tenant_id)").getResultList())
                .stream().map(Number::longValue).toList();
    }

}
