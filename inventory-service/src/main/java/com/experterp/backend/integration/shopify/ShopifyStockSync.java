package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.service.AllocationService;
import com.experterp.backend.service.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Keeps Shopify's stock right (7.6): every product linked to Shopify shows its AVAILABLE stock over all warehouses
 * (on hand − reserved for orders, whole units, never below 0; business-rules.md §1, §5). It is set as Shopify's
 * "available" quantity, so Shopify does not take its own unshipped orders off a second time.
 * <p>
 * What Shopify was last given is kept per product: a run compares and sends only what changed, in batches of 100.
 * Nothing has to call it when stock moves (receipts, shipments, orders confirmed or cancelled...): a worker runs it
 * every 20 seconds, so a missed change cannot exist. A full re-send runs every night and on request.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopifyStockSync {

    static final int BATCH = 100;

    public record Result(int checked, int sent, int unchanged, int failed, List<String> problems) {
        String summary() {
            return sent + " sent, " + unchanged + " unchanged" + (failed > 0 ? ", " + failed + " failed" : "");
        }
    }

    record Want(Long linkId, Long productId, String sku, String inventoryItemId, int available, Integer sent) {}

    private final ShopifyConnectionRepository connectionRepository;
    private final ShopifyConnectionService connectionService;
    private final ShopifyClient client;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final TransactionTemplate tx;
    private final com.experterp.backend.service.KitService kits;

    // One run per organisation at a time
    private final Map<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** Every connected organisation with products in Shopify (the scheduler calls this). */
    public void syncAll(boolean full) {
        List<Long> tenants = jdbc.getJdbcTemplate().queryForList("""
                SELECT c.tenant_id FROM shopify_connections c
                WHERE c.status = 'CONNECTED' AND EXISTS (SELECT 1 FROM shopify_product_links k WHERE k.tenant_id = c.tenant_id)""", Long.class);
        for (Long tenantId : tenants) {
            ReentrantLock lock = locks.computeIfAbsent(tenantId, k -> new ReentrantLock());
            if (!lock.tryLock()) continue;   // a run is going on
            Long previous = TenantContext.get();
            TenantContext.set(tenantId);
            try {
                run(full);
            } catch (RuntimeException e) {
                log.warn("Shopify stock sync for organisation {} failed: {}", tenantId, e.getMessage());
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
                lock.unlock();
            }
        }
    }

    /** The current organisation, now; waits for a run already going on. {@code full}: send every product. */
    public Result sync(boolean full) {
        ReentrantLock lock = locks.computeIfAbsent(TenantContext.get(), k -> new ReentrantLock());
        lock.lock();
        try {
            return run(full);
        } finally {
            lock.unlock();
        }
    }

    /** Available stock per linked product, as Shopify should show it, and what it was last given. */
    List<Want> wanted() {
        return wanted(null);
    }

    /** One product only (null = all linked products). */
    List<Want> wanted(Long productId) {
        Map<String, Object> params = new HashMap<>();
        params.put("t", TenantContext.get());
        params.put("p", productId);
        List<Want> rows = jdbc.query("""
                SELECT k.id, k.product_id, p.sku, k.shopify_inventory_item_id, k.stock_sent,
                       GREATEST(0, FLOOR(COALESCE(oh.qty, 0) - COALESCE(r.qty, 0)))::int AS available
                FROM shopify_product_links k
                JOIN products p ON p.id = k.product_id
                LEFT JOIN (SELECT product_id, SUM(CASE WHEN type IN (""" + StockMovementRepository.IN_TYPES + """
                ) THEN quantity ELSE -quantity END) AS qty
                           FROM stock_movements WHERE tenant_id = :t GROUP BY product_id) oh ON oh.product_id = k.product_id
                LEFT JOIN (""" + AllocationService.RESERVED_PER_PRODUCT_SQL + """
                ) r ON r.product_id = k.product_id
                WHERE k.tenant_id = :t AND k.shopify_inventory_item_id IS NOT NULL
                  AND (CAST(:p AS bigint) IS NULL OR k.product_id = :p)
                ORDER BY k.product_id""", params,
                (rs, i) -> new Want(rs.getLong("id"), rs.getLong("product_id"), rs.getString("sku"),
                        rs.getString("shopify_inventory_item_id"), rs.getInt("available"),
                        (Integer) rs.getObject("stock_sent")));
        // A kit shows the kits its components can make (P2)
        Map<Long, Integer> kitStock = kits.availableKits(rows.stream().map(Want::productId).toList());
        if (kitStock.isEmpty()) return rows;
        return rows.stream().map(w -> kitStock.containsKey(w.productId())
                ? new Want(w.linkId(), w.productId(), w.sku(), w.inventoryItemId(), kitStock.get(w.productId()), w.sent()) : w).toList();
    }

    /** Available stock of every product, all warehouses, as Shopify should show it (for the product sync). */
    Map<Long, Integer> availableByProduct() {
        Map<Long, Integer> out = new HashMap<>();
        jdbc.query("""
                SELECT p.id, GREATEST(0, FLOOR(COALESCE(oh.qty, 0) - COALESCE(r.qty, 0)))::int AS available
                FROM products p
                LEFT JOIN (SELECT product_id, SUM(CASE WHEN type IN (""" + StockMovementRepository.IN_TYPES + """
                ) THEN quantity ELSE -quantity END) AS qty
                           FROM stock_movements WHERE tenant_id = :t GROUP BY product_id) oh ON oh.product_id = p.id
                LEFT JOIN (""" + AllocationService.RESERVED_PER_PRODUCT_SQL + """
                ) r ON r.product_id = p.id
                WHERE p.tenant_id = :t""", Map.of("t", TenantContext.get()),
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getLong("id"), rs.getInt("available")));
        out.putAll(kits.availableKits(out.keySet()));
        return out;
    }

    /** How many linked products Shopify shows a different number for than it should (not sent yet). */
    public long waiting() {
        return wanted().stream().filter(w -> w.sent() == null || w.sent() != w.available()).count();
    }

    private Result run(boolean full) {
        ShopifyConnection c = tx.execute(s -> connectionRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new ResourceNotFoundException("No Shopify store is connected")));
        List<Want> wanted = wanted();
        List<Want> changed = wanted.stream().filter(w -> full || w.sent() == null || w.sent() != w.available()).toList();
        if (changed.isEmpty() && !full) return new Result(wanted.size(), 0, wanted.size(), 0, List.of());

        int sent = 0, failed = 0;
        List<String> problems = new ArrayList<>();
        String error = null;
        try {
            String token = connectionService.accessToken();
            String location = location(c, token);
            for (int i = 0; i < changed.size(); i += BATCH) {
                List<Want> batch = changed.subList(i, Math.min(i + BATCH, changed.size()));
                try {
                    set(c.getShopDomain(), token, location, batch);
                    markSent(batch);
                    sent += batch.size();
                } catch (ShopifyApiException e) {
                    // One refused item would sink its batch: send them one by one so it names itself
                    for (Want one : batch) {
                        try {
                            set(c.getShopDomain(), token, location, List.of(one));
                            markSent(List.of(one));
                            sent++;
                        } catch (ShopifyApiException single) {
                            failed++;
                            if (problems.size() < 50) problems.add(one.sku() + ": " + single.getMessage());
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        Result result = new Result(wanted.size(), sent, wanted.size() - changed.size(), failed, problems);
        String err = error != null ? error : failed > 0 ? failed + " product(s) refused: " + String.join("; ", problems.subList(0, Math.min(3, problems.size()))) : null;
        tx.executeWithoutResult(s -> connectionRepository.findById(c.getId()).ifPresent(fresh -> {
            fresh.setStockSyncedAt(LocalDateTime.now());
            fresh.setStockSyncResult(result.summary());
            fresh.setStockSyncError(err);
            if (full) auditService.log("SHOPIFY", fresh.getId(), "STOCK_SYNC", Map.of("result", result.summary(), "error", String.valueOf(err)));
        }));
        if (error != null) throw new ShopifyApiException(error);
        return result;
    }

    // The store's location for stock, found once and kept
    private String location(ShopifyConnection c, String token) {
        if (c.getLocationId() != null) return c.getLocationId();
        JsonNode loc = client.graphql(c.getShopDomain(), token, ShopifyProductSyncService.LOCATION_QUERY, Map.of()).path("location");
        String id = loc.path("id").asText(null);
        if (id == null) throw new ShopifyApiException("Shopify has no location to keep stock at");
        tx.executeWithoutResult(s -> connectionRepository.findById(c.getId()).ifPresent(fresh -> fresh.setLocationId(id)));
        c.setLocationId(id);
        return id;
    }

    private void set(String shop, String token, String location, List<Want> batch) {
        List<Map<String, Object>> quantities = new ArrayList<>();
        for (Want w : batch) {
            Map<String, Object> q = new HashMap<>();
            q.put("inventoryItemId", w.inventoryItemId());
            q.put("locationId", location);
            q.put("quantity", w.available());
            // Set it whatever Shopify has now: the ERP is the source of truth
            q.put("changeFromQuantity", null);
            quantities.add(q);
        }
        Map<String, Object> input = Map.of("name", "available", "reason", "correction", "quantities", quantities);
        JsonNode result = client.graphql(shop, token, ShopifyProductSyncService.SET_QUANTITIES,
                Map.of("input", input, "idempotencyKey", UUID.randomUUID().toString())).path("inventorySetQuantities");
        JsonNode errors = result.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(e.path("message").asText()));
            throw new ShopifyApiException("Shopify refused the stock: " + String.join("; ", messages));
        }
    }

    private void markSent(List<Want> batch) {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.getJdbcTemplate().batchUpdate("UPDATE shopify_product_links SET stock_sent = ?, stock_sent_at = ? WHERE id = ?",
                batch.stream().map(w -> new Object[]{w.available(), now, w.linkId()}).toList());
    }
}
