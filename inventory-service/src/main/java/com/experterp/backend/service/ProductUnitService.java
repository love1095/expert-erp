package com.experterp.backend.service;

import com.experterp.backend.entity.Product;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Phase P3: units of measure. A product's own unit (Each) is its base: stock and FIFO cost are kept in it. Extra units
 * (Pair = 2, Box of 100 = 100, Carton = 500) can be sold or bought; an order line stays in its unit, its stock moves
 * quantity × factor. A unit may have its own price; else it is the base price × factor.
 */
@Service
@RequiredArgsConstructor
public class ProductUnitService {

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final AuditService auditService;

    public record Unit(Long id, String name, BigDecimal factor, String barcode, BigDecimal sellPrice, BigDecimal buyPrice) {}

    public record UnitRequest(String name, BigDecimal factor, String barcode, BigDecimal sellPrice, BigDecimal buyPrice) {}

    /** A line's unit: its name as shown, and how many base units one of it holds. */
    public record Resolved(String name, BigDecimal factor, Unit unit) {
        public boolean base() { return unit == null; }
    }

    @Transactional(readOnly = true)
    public List<Unit> units(Long productId) {
        product(productId);
        return jdbc.query("SELECT * FROM product_units WHERE tenant_id = ? AND product_id = ? ORDER BY factor, lower(name)", (rs, n) -> new Unit(rs.getLong("id"),
                rs.getString("name"), rs.getBigDecimal("factor"), rs.getString("barcode"), rs.getBigDecimal("sell_price"), rs.getBigDecimal("buy_price")),
                TenantContext.get(), productId);
    }

    /** Replaces the product's extra units. A unit in use on an open order keeps its factor. */
    @Transactional
    public List<Unit> save(Long productId, List<UnitRequest> list) {
        Product p = product(productId);
        if (p.getType() == Product.Type.SERVICE) throw new IllegalArgumentException("A service has no units of measure");
        Set<String> names = new HashSet<>();
        for (UnitRequest u : list) {
            String name = u.name() == null ? "" : u.name().trim();
            if (name.isEmpty()) throw new IllegalArgumentException("Give each unit a name");
            if (name.equalsIgnoreCase(p.getUnit())) throw new IllegalArgumentException(name + " is the product's own unit");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException(name + " is listed twice");
            if (u.factor() == null || u.factor().signum() <= 0) throw new IllegalArgumentException(name + ": give how many " + p.getUnit() + " it holds");
            if (u.factor().compareTo(BigDecimal.ONE) == 0) throw new IllegalArgumentException(name + " holds 1 " + p.getUnit() + ": that is the product's own unit");
        }
        Long t = TenantContext.get();
        // An open order line in a unit stops the unit being removed or changed
        List<Map<String, Object>> inUse = jdbc.queryForList("""
                SELECT DISTINCT l.unit_name, l.unit_factor FROM sale_order_lines l JOIN sale_orders so ON so.id = l.sale_order_id
                 WHERE so.tenant_id = ? AND l.product_id = ? AND l.unit_name IS NOT NULL AND so.status IN ('DRAFT', 'CONFIRMED', 'QUOTE')
                UNION SELECT DISTINCT l.unit_name, l.unit_factor FROM purchase_order_lines l JOIN purchase_orders po ON po.id = l.purchase_order_id
                 WHERE po.tenant_id = ? AND l.product_id = ? AND l.unit_name IS NOT NULL AND po.status IN ('DRAFT', 'ORDERED')""", t, productId, t, productId);
        for (Map<String, Object> used : inUse) {
            String name = (String) used.get("unit_name");
            BigDecimal factor = (BigDecimal) used.get("unit_factor");
            boolean kept = list.stream().anyMatch(u -> u.name().trim().equalsIgnoreCase(name) && u.factor().compareTo(factor) == 0);
            if (!kept) throw new IllegalStateException(name + " is on open orders: it cannot be removed or changed until they are done");
        }
        jdbc.update("DELETE FROM product_units WHERE tenant_id = ? AND product_id = ?", t, productId);
        for (UnitRequest u : list) {
            jdbc.update("INSERT INTO product_units (tenant_id, product_id, name, factor, barcode, sell_price, buy_price) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    t, productId, u.name().trim(), u.factor(), blank(u.barcode()), u.sellPrice(), u.buyPrice());
        }
        auditService.log("PRODUCT", productId, "UNITS", Map.of("units", list.stream().map(u -> u.name().trim() + " = " + u.factor().stripTrailingZeros().toPlainString()).toList()));
        return units(productId);
    }

    /** The unit a line asks for: empty or the product's own unit = the base (factor 1); otherwise one of its units. */
    @Transactional(readOnly = true)
    public Resolved resolve(Product p, String unitName) {
        if (unitName == null || unitName.isBlank() || unitName.trim().equalsIgnoreCase(p.getUnit())) return new Resolved(null, BigDecimal.ONE, null);
        return units(p.getId()).stream().filter(u -> u.name().equalsIgnoreCase(unitName.trim())).findFirst()
                .map(u -> new Resolved(u.name(), u.factor(), u))
                .orElseThrow(() -> new IllegalArgumentException(p.getSku() + " has no unit \"" + unitName.trim() + "\""));
    }

    /** The price of one of this unit: its own, else the base price × factor. */
    public static BigDecimal price(Resolved r, BigDecimal basePrice, boolean selling) {
        if (r.unit() != null) {
            BigDecimal own = selling ? r.unit().sellPrice() : r.unit().buyPrice();
            if (own != null) return own;
        }
        return basePrice == null ? null : basePrice.multiply(r.factor()).setScale(4, RoundingMode.HALF_UP);
    }

    private Product product(Long id) {
        return products.findById(id).orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
