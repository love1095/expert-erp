package com.experterp.backend.service;

import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase N5: stock adjustments with a reason (damage, write-off, sample, found, theft, count, other), valued at the
 * product's FIFO cost in that warehouse; one worth more than the organisation's approval value waits for a manager.
 * Posted as ADJUSTMENT_IN / ADJUSTMENT_OUT movements (so the ledger and the Xero export journal them with the existing
 * mapping), from or into a bin when one is given; never below zero.
 */
@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    public static final List<String> REASONS = List.of("DAMAGE", "WRITE_OFF", "SAMPLE", "FOUND", "THEFT", "COUNT", "OTHER");

    private final JdbcTemplate jdbc;
    private final StockMovementService movements;
    private final ProductRepository products;
    private final WarehouseRepository warehouses;
    private final BinService bins;
    private final AuditService auditService;

    public record Request(Long id, Long productId, String sku, String productName, Long warehouseId, String warehouse, Long binId, String bin,
                          BigDecimal quantity, String reasonCode, String note, BigDecimal unitCost, BigDecimal value, String status, Long movementId,
                          String requestedBy, LocalDateTime requestedAt, String decidedBy, LocalDateTime decidedAt, String decisionNote) {}

    public record AdjustRequest(Long productId, Long warehouseId, Long binId, BigDecimal quantity, String reasonCode, String note) {}

    // ---- settings ----

    @Transactional(readOnly = true)
    public BigDecimal approvalValue() {
        List<BigDecimal> v = jdbc.queryForList("SELECT adjustment_approval_value FROM inventory_settings WHERE tenant_id = ?", BigDecimal.class, TenantContext.get());
        return v.isEmpty() ? null : v.get(0);
    }

    @Transactional
    public BigDecimal setApprovalValue(BigDecimal value) {
        requireManager();
        if (value != null && value.signum() < 0) throw new IllegalArgumentException("The approval value cannot be negative");
        jdbc.update("""
                INSERT INTO inventory_settings (tenant_id, adjustment_approval_value, updated_at, updated_by) VALUES (?, ?, now(), ?)
                ON CONFLICT (tenant_id) DO UPDATE SET adjustment_approval_value = EXCLUDED.adjustment_approval_value, updated_at = now(), updated_by = EXCLUDED.updated_by""",
                TenantContext.get(), value, auditService.currentActorName());
        auditService.log("INVENTORY", null, "ADJUSTMENT_APPROVAL_VALUE", Map.of("value", String.valueOf(value)));
        return approvalValue();
    }

    // ---- adjusting ----

    /** Posts the adjustment, or keeps it waiting for a manager when it is worth more than the approval value. */
    @Transactional
    public Request adjust(AdjustRequest r) {
        if (r.quantity() == null || r.quantity().signum() == 0) throw new IllegalArgumentException("Give the quantity: positive to add, negative to take away");
        String reason = r.reasonCode() == null ? "" : r.reasonCode().toUpperCase(Locale.ROOT);
        if (!REASONS.contains(reason)) throw new IllegalArgumentException("Choose a reason: " + String.join(", ", REASONS));
        Product p = products.findById(r.productId()).orElseThrow(() -> new IllegalArgumentException("Product not found"));
        Warehouse w = warehouses.findById(r.warehouseId()).orElseThrow(() -> new IllegalArgumentException("Warehouse not found"));
        if (r.binId() != null) {
            Map<String, Object> b = bins.bin(r.binId());
            if (((Number) b.get("warehouse_id")).longValue() != w.getId()) throw new IllegalArgumentException("That bin is in another warehouse");
        }
        if (r.quantity().signum() < 0) checkAvailable(p.getId(), w.getId(), r.binId(), r.quantity().negate());
        BigDecimal unit = unitCost(p, w.getId());
        BigDecimal value = r.quantity().abs().multiply(unit).setScale(2, RoundingMode.HALF_UP);
        BigDecimal limit = approvalValue();
        boolean waits = limit != null && value.compareTo(limit) > 0;
        Long id = jdbc.queryForObject("""
                INSERT INTO stock_adjustment_requests (tenant_id, product_id, warehouse_id, bin_id, quantity, reason_code, note, unit_cost, value, status, requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, TenantContext.get(), p.getId(), w.getId(), r.binId(), r.quantity(), reason,
                cut(r.note(), 500), unit, value, waits ? "PENDING" : "POSTED", auditService.currentActorName());
        if (!waits) post(id, p, w, r.binId(), r.quantity(), reason, unit, r.note(), false);
        auditService.log("STOCK_ADJUSTMENT", id, waits ? "REQUESTED" : "POSTED", Map.of("sku", p.getSku(), "quantity", r.quantity(), "reason", reason, "value", value));
        return request(id);
    }

    /** A manager approves a waiting adjustment: posted now (the stock checked again). */
    @Transactional
    public Request approve(Long id, String note) {
        requireManager();
        Map<String, Object> r = pending(id);
        Product p = products.findById(((Number) r.get("product_id")).longValue()).orElseThrow();
        Warehouse w = warehouses.findById(((Number) r.get("warehouse_id")).longValue()).orElseThrow();
        Long bin = r.get("bin_id") == null ? null : ((Number) r.get("bin_id")).longValue();
        BigDecimal qty = (BigDecimal) r.get("quantity");
        if (qty.signum() < 0) checkAvailable(p.getId(), w.getId(), bin, qty.negate());
        post(id, p, w, bin, qty, (String) r.get("reason_code"), (BigDecimal) r.get("unit_cost"), (String) r.get("note"), false);
        jdbc.update("UPDATE stock_adjustment_requests SET status = 'POSTED', decided_by = ?, decided_at = now(), decision_note = ? WHERE id = ?",
                auditService.currentActorName(), cut(note, 500), id);
        auditService.log("STOCK_ADJUSTMENT", id, "APPROVED", Map.of("sku", p.getSku(), "quantity", qty));
        return request(id);
    }

    @Transactional
    public Request reject(Long id, String note) {
        requireManager();
        pending(id);
        jdbc.update("UPDATE stock_adjustment_requests SET status = 'REJECTED', decided_by = ?, decided_at = now(), decision_note = ? WHERE id = ?",
                auditService.currentActorName(), cut(note, 500), id);
        auditService.log("STOCK_ADJUSTMENT", id, "REJECTED", Map.of("note", String.valueOf(note)));
        return request(id);
    }

    /** Posting without the approval step: a stock take a manager posts (its variances are the count). */
    @Transactional
    public Long postDirect(Product p, Warehouse w, Long binId, BigDecimal qty, String reason, String note) {
        BigDecimal unit = unitCost(p, w.getId());
        Long id = jdbc.queryForObject("""
                INSERT INTO stock_adjustment_requests (tenant_id, product_id, warehouse_id, bin_id, quantity, reason_code, note, unit_cost, value, status, requested_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?) RETURNING id""", Long.class, TenantContext.get(), p.getId(), w.getId(), binId, qty, reason,
                cut(note, 500), unit, qty.abs().multiply(unit).setScale(2, RoundingMode.HALF_UP), auditService.currentActorName());
        post(id, p, w, binId, qty, reason, unit, note, true);
        return id;
    }

    // exactPlace: no bin means unbinned stock (a stock take line), not "wherever the default bin is"
    private void post(Long requestId, Product p, Warehouse w, Long binId, BigDecimal qty, String reason, BigDecimal unit, String note, boolean exactPlace) {
        String ref = "ADJ-" + requestId;
        String notes = reason + (note == null || note.isBlank() ? "" : ": " + note.trim());
        StockMovement m;
        if (qty.signum() > 0) {
            // In goes to the active default bin (N1 listener), else unbinned; then put it where it was found
            Long from = jdbc.queryForList("""
                    SELECT pb.bin_id FROM product_bins pb JOIN warehouse_bins b ON b.id = pb.bin_id AND b.active
                     WHERE pb.tenant_id = ? AND pb.product_id = ? AND pb.warehouse_id = ?""", Long.class, TenantContext.get(), p.getId(), w.getId())
                    .stream().findFirst().orElse(null);
            m = movements.createMovement(p, w, qty, MovementType.ADJUSTMENT_IN, ReferenceType.ADJUSTMENT, ref, notes, null, unit);
            if ((binId != null || exactPlace) && !Objects.equals(from, binId)) bins.move(p.getId(), w.getId(), from, binId, qty, ref);
        } else {
            BigDecimal out = qty.negate();
            if (binId != null) bins.post(p.getId(), w.getId(), binId, qty, "ADJUSTED", ref);
            movements.assertSufficientStock(p.getId(), w.getId(), out);
            m = movements.createMovement(p, w, out, MovementType.ADJUSTMENT_OUT, ReferenceType.ADJUSTMENT, ref, notes);
        }
        jdbc.update("UPDATE stock_movements SET reason_code = ? WHERE id = ?", reason, m.getId());
        jdbc.update("UPDATE stock_adjustment_requests SET movement_id = ? WHERE id = ?", m.getId(), requestId);
    }

    @Transactional(readOnly = true)
    public List<Request> requests(String status) {
        return jdbc.queryForList("SELECT id FROM stock_adjustment_requests WHERE tenant_id = ? AND (?::varchar IS NULL OR status = ?::varchar) ORDER BY requested_at DESC LIMIT 200",
                Long.class, TenantContext.get(), status, status).stream().map(this::request).toList();
    }

    @Transactional(readOnly = true)
    public Request request(Long id) {
        return jdbc.query("""
                SELECT r.*, p.sku, p.name, w.name AS warehouse, b.code AS bin FROM stock_adjustment_requests r
                  JOIN products p ON p.id = r.product_id JOIN warehouses w ON w.id = r.warehouse_id LEFT JOIN warehouse_bins b ON b.id = r.bin_id
                 WHERE r.tenant_id = ? AND r.id = ?""", rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Adjustment not found");
            return new Request(rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"), rs.getString("name"), rs.getLong("warehouse_id"),
                    rs.getString("warehouse"), rs.getObject("bin_id") == null ? null : rs.getLong("bin_id"), rs.getString("bin"), rs.getBigDecimal("quantity"),
                    rs.getString("reason_code"), rs.getString("note"), rs.getBigDecimal("unit_cost"), rs.getBigDecimal("value"), rs.getString("status"),
                    rs.getObject("movement_id") == null ? null : rs.getLong("movement_id"), rs.getString("requested_by"), ts(rs.getTimestamp("requested_at")),
                    rs.getString("decided_by"), ts(rs.getTimestamp("decided_at")), rs.getString("decision_note"));
        }, TenantContext.get(), id);
    }

    // ---- shared ----

    /** What one more unit is worth here: the FIFO layers' average in this warehouse, else the product's cost price. */
    BigDecimal unitCost(Product p, Long warehouseId) {
        BigDecimal avg = jdbc.queryForObject("""
                SELECT SUM(remaining * unit_cost) / NULLIF(SUM(remaining), 0) FROM cost_layers WHERE tenant_id = ? AND product_id = ? AND warehouse_id = ? AND remaining > 0""",
                BigDecimal.class, TenantContext.get(), p.getId(), warehouseId);
        if (avg != null) return avg.setScale(6, RoundingMode.HALF_UP);
        return p.getCostPrice() == null ? BigDecimal.ZERO : p.getCostPrice();
    }

    private void checkAvailable(Long productId, Long warehouseId, Long binId, BigDecimal out) {
        BinService.ProductBins pb = bins.productBins(productId, warehouseId);
        if (out.compareTo(pb.warehouseStock()) > 0) throw new IllegalStateException("Only " + pb.warehouseStock().stripTrailingZeros().toPlainString()
                + " in the warehouse: stock cannot go below zero");
        if (binId != null) {
            BigDecimal inBin = pb.bins().stream().filter(b -> b.binId().equals(binId)).map(BinService.BinStock::quantity).findFirst().orElse(BigDecimal.ZERO);
            if (out.compareTo(inBin) > 0) throw new IllegalStateException("Only " + inBin.stripTrailingZeros().toPlainString() + " in that bin");
        }
    }

    private Map<String, Object> pending(Long id) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT * FROM stock_adjustment_requests WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        if (r.isEmpty()) throw new IllegalArgumentException("Adjustment not found");
        if (!"PENDING".equals(r.get(0).get("status"))) throw new IllegalStateException("This adjustment is " + String.valueOf(r.get(0).get("status")).toLowerCase());
        return r.get(0);
    }

    static void requireManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SUPER_ADMIN").contains(a.getAuthority()));
        if (!ok) throw new AccessDeniedException("Only a manager or admin can do this");
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
