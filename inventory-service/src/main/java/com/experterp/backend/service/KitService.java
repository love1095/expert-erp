package com.experterp.backend.service;

import com.experterp.backend.entity.Product;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Phase P2: kits and bundles (Cin7: Bill of materials, kit). A KIT product is made of components; it holds no stock
 * of its own. Its stock is what the components can make — the minimum over the components of their stock divided by
 * what one kit takes, in whole kits. Selling one reserves and ships the components (at their FIFO cost, so the kit's
 * cost is the sum of its components'), picks them from their bins, and a return puts them back.
 */
@Service
@RequiredArgsConstructor
public class KitService {

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final AllocationService allocation;
    private final AuditService auditService;

    public record Component(Long componentId, String sku, String name, String unit, BigDecimal quantity) {}

    public record ComponentRequest(Long componentId, BigDecimal quantity) {}

    /** What a kit can be made of in one warehouse: on hand (from on-hand components) and available (from available ones). */
    public record Buildable(Long warehouseId, String warehouse, BigDecimal onHand, BigDecimal available) {}

    @Transactional(readOnly = true)
    public List<Component> components(Long kitId) {
        products.findById(kitId).orElseThrow(() -> new IllegalArgumentException("Product not found"));
        return jdbc.query("""
                SELECT k.component_id, p.sku, p.name, p.unit, k.quantity FROM kit_components k JOIN products p ON p.id = k.component_id
                 WHERE k.tenant_id = ? AND k.kit_id = ? ORDER BY p.sku""", (rs, n) -> new Component(rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getBigDecimal(5)), TenantContext.get(), kitId);
    }

    /** Replaces a kit's components. Components are stocked products (not kits, not services), each once, quantity above 0. */
    @Transactional
    public List<Component> setComponents(Long kitId, List<ComponentRequest> list) {
        Product kit = products.findById(kitId).orElseThrow(() -> new IllegalArgumentException("Product not found"));
        if (kit.getType() != Product.Type.KIT) throw new IllegalArgumentException(kit.getSku() + " is not a kit: make its type Kit first");
        if (list == null || list.isEmpty()) throw new IllegalArgumentException("A kit needs at least one component");
        Set<Long> seen = new HashSet<>();
        for (ComponentRequest c : list) {
            if (c.quantity() == null || c.quantity().signum() <= 0) throw new IllegalArgumentException("Give each component a quantity above 0");
            if (!seen.add(c.componentId())) throw new IllegalArgumentException("A component is listed twice");
            Product p = products.findById(c.componentId()).orElseThrow(() -> new IllegalArgumentException("Component not found"));
            if (p.getId().equals(kitId)) throw new IllegalArgumentException("A kit cannot contain itself");
            if (p.getType() != Product.Type.STOCK) throw new IllegalArgumentException(p.getSku() + " cannot be a component: only stocked products can");
        }
        // Not while orders still have the kit to ship: their reservations would change under them
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id
                 WHERE so.tenant_id = ? AND l.product_id = ? AND so.status IN ('DRAFT', 'CONFIRMED') AND l.quantity - l.fulfilled_quantity - l.cancelled_quantity > 0""",
                Integer.class, TenantContext.get(), kitId);
        if (open != null && open > 0 && !components(kitId).isEmpty()) {
            throw new IllegalStateException(kit.getSku() + " is on " + open + " open order line" + (open == 1 ? "" : "s") + ": ship or cancel them before changing its components");
        }
        jdbc.update("DELETE FROM kit_components WHERE tenant_id = ? AND kit_id = ?", TenantContext.get(), kitId);
        for (ComponentRequest c : list) {
            jdbc.update("INSERT INTO kit_components (tenant_id, kit_id, component_id, quantity) VALUES (?, ?, ?, ?)", TenantContext.get(), kitId,
                    c.componentId(), c.quantity());
        }
        auditService.log("PRODUCT", kitId, "KIT_COMPONENTS", Map.of("components", list.size()));
        return components(kitId);
    }

    /** The product is no longer a kit: its components go (its sales so far keep what they shipped). */
    @Transactional
    public void clearComponents(Long kitId) {
        if (jdbc.update("DELETE FROM kit_components WHERE tenant_id = ? AND kit_id = ?", TenantContext.get(), kitId) > 0) {
            auditService.log("PRODUCT", kitId, "KIT_COMPONENTS", Map.of("components", 0));
        }
    }

    /** The components of a kit and how many of each one kit takes; empty for any other product. */
    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> recipe(Long productId) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        jdbc.query("SELECT component_id, quantity FROM kit_components WHERE tenant_id = ? AND kit_id = ? ORDER BY component_id",
                rs -> { out.put(rs.getLong(1), rs.getBigDecimal(2)); }, TenantContext.get(), productId);
        return out;
    }

    /** Per warehouse with any of the components: the kits the stock can make. */
    @Transactional(readOnly = true)
    public List<Buildable> buildable(Long kitId) {
        Map<Long, BigDecimal> recipe = recipe(kitId);
        if (recipe.isEmpty()) return List.of();
        Long t = TenantContext.get();
        Map<String, BigDecimal> onHand = new HashMap<>();
        Map<Long, String> names = new TreeMap<>();
        jdbc.query("""
                SELECT m.product_id, m.warehouse_id, w.name, SUM(CASE WHEN m.type IN (%s) THEN m.quantity ELSE -m.quantity END)
                  FROM stock_movements m JOIN warehouses w ON w.id = m.warehouse_id
                 WHERE m.tenant_id = ? AND m.product_id = ANY(?::bigint[]) GROUP BY m.product_id, m.warehouse_id, w.name""".formatted(StockMovementRepository.IN_TYPES),
                rs -> {
                    onHand.put(AllocationService.key(rs.getLong(1), rs.getLong(2)), rs.getBigDecimal(4));
                    names.put(rs.getLong(2), rs.getString(3));
                }, t, (Object) recipe.keySet().toArray(Long[]::new));
        Map<String, BigDecimal> reserved = allocation.reservedByProductAndWarehouse(recipe.keySet());
        List<Buildable> out = new ArrayList<>();
        names.forEach((wid, name) -> {
            BigDecimal have = null, free = null;
            for (Map.Entry<Long, BigDecimal> c : recipe.entrySet()) {
                String key = AllocationService.key(c.getKey(), wid);
                BigDecimal oh = onHand.getOrDefault(key, BigDecimal.ZERO);
                BigDecimal av = oh.subtract(reserved.getOrDefault(key, BigDecimal.ZERO));
                BigDecimal k1 = kits(oh, c.getValue()), k2 = kits(av, c.getValue());
                have = have == null ? k1 : have.min(k1);
                free = free == null ? k2 : free.min(k2);
            }
            out.add(new Buildable(wid, name, have, free));
        });
        return out;
    }

    /** Kits available across all warehouses (what Shopify should show): each warehouse makes its own whole kits. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> availableKits(Collection<Long> productIds) {
        Map<Long, Integer> out = new HashMap<>();
        if (productIds.isEmpty()) return out;
        List<Long> kits = jdbc.queryForList("SELECT DISTINCT kit_id FROM kit_components WHERE tenant_id = ? AND kit_id = ANY(?::bigint[])", Long.class,
                TenantContext.get(), (Object) productIds.toArray(Long[]::new));
        for (Long k : kits) {
            out.put(k, buildable(k).stream().map(Buildable::available).reduce(BigDecimal.ZERO, BigDecimal::add).max(BigDecimal.ZERO).intValue());
        }
        return out;
    }

    private static BigDecimal kits(BigDecimal stock, BigDecimal per) {
        return stock.signum() <= 0 ? BigDecimal.ZERO : stock.divide(per, 0, RoundingMode.FLOOR);
    }
}
