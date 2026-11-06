package com.experterp.backend.service;

import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.entity.Fulfilment;
import com.experterp.backend.entity.SaleOrder;
import com.experterp.backend.entity.SaleOrderLine;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.FulfilmentRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase N2 / N3: pick, pack and ship (spec §4), like Cin7's fulfilment tabs.
 * <ul>
 *   <li><b>Pick list</b> for one order or a wave of orders of one warehouse: what each line has allocated and not yet
 *       picked, taken from its bins in walk order (unbinned stock last), sorted by the walk; printable; picked by hand or
 *       by scanning (N4). Confirming it takes the stock out of its bins (into the packing area: unbinned).</li>
 *   <li><b>Pack</b>: picked quantities into cartons with weight and size; a despatch note per order.</li>
 *   <li><b>Ship</b> the cartons packed: one shipment through the existing fulfilment (part shipments and backorders as
 *       D2), with the carrier and tracking number.</li>
 * </ul>
 * The order's warehouse stage follows: Authorised → Picking → Picked → Packed → Shipped, beside its fulfilment status.
 */
@Service
@RequiredArgsConstructor
public class PickPackService {

    private static final DateTimeFormatter UK = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final SaleOrderRepository orders;
    private final SaleOrderService saleOrderService;
    private final FulfilmentRepository fulfilments;
    private final AllocationService allocation;
    private final BinService bins;
    private final PdfRenderer renderer;
    private final DocumentService documents;
    private final AuditService auditService;

    // ---- pick lists (N2) ----

    public record PickLine(Long id, Long saleOrderId, String orderNumber, Long saleOrderLineId, Long productId, String sku, String name, String barcode,
                           Long binId, String bin, int walkOrder, BigDecimal quantity, BigDecimal picked) {}

    public record PickList(Long id, String number, Long warehouseId, String warehouse, String status, List<String> orders, List<PickLine> lines,
                           LocalDateTime createdAt, String createdBy, LocalDateTime pickedAt, String pickedBy) {}

    /** A pick list for these orders (all of one warehouse, confirmed): what is allocated and not yet picked, in walk order. */
    @Transactional
    public PickList create(List<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) throw new IllegalArgumentException("Choose the orders to pick");
        Long t = TenantContext.get();
        Long warehouseId = null;
        List<SaleOrder> picked = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(orderIds)) {
            SaleOrder so = orders.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Sale order " + id + " not found"));
            if (so.getStatus() != SaleOrder.Status.CONFIRMED) throw new IllegalStateException(so.getOrderNumber() + " is not an open order");
            if (warehouseId != null && !warehouseId.equals(so.getWarehouse().getId())) throw new IllegalArgumentException("A pick list is for one warehouse: "
                    + so.getOrderNumber() + " ships from another");
            warehouseId = so.getWarehouse().getId();
            picked.add(so);
        }
        Map<Long, AllocationService.LineAllocation> alloc = allocation.forOrders(orderIds);
        // What bins already promised to open pick lists
        Map<Long, BigDecimal> promised = new HashMap<>();
        jdbc.query("""
                SELECT l.bin_id, SUM(l.quantity - l.picked) FROM pick_list_lines l JOIN pick_lists p ON p.id = l.pick_list_id
                 WHERE p.tenant_id = ? AND p.status = 'OPEN' AND l.bin_id IS NOT NULL GROUP BY l.bin_id""",
                rs -> { promised.put(rs.getLong(1), rs.getBigDecimal(2)); }, t);
        Long listId = jdbc.queryForObject("INSERT INTO pick_lists (tenant_id, warehouse_id, created_by) VALUES (?, ?, ?) RETURNING id",
                Long.class, t, warehouseId, auditService.currentActorName());
        int lines = 0;
        for (SaleOrder so : picked) {
            for (SaleOrderLine l : so.getLines()) {
                AllocationService.LineAllocation a = alloc.get(l.getId());
                if (a == null) continue;
                BigDecimal pickable = a.allocated().min(a.remaining()).subtract(pickedNotShipped(l)).subtract(onOpenLists(l.getId())).max(BigDecimal.ZERO);
                if (pickable.signum() <= 0) continue;
                // From its bins in walk order (what open lists have not promised), the rest from unbinned stock
                BinService.ProductBins pb = bins.productBins(l.getProduct().getId(), so.getWarehouse().getId());
                for (BinService.BinStock b : pb.bins()) {
                    if (pickable.signum() <= 0) break;
                    BigDecimal free = b.quantity().subtract(promised.getOrDefault(b.binId(), BigDecimal.ZERO));
                    BigDecimal take = pickable.min(free);
                    if (take.signum() <= 0) continue;
                    insertLine(t, listId, so, l, b.binId(), b.walkOrder(), take);
                    promised.merge(b.binId(), take, BigDecimal::add);
                    pickable = pickable.subtract(take);
                    lines++;
                }
                if (pickable.signum() > 0) {
                    insertLine(t, listId, so, l, null, Integer.MAX_VALUE, pickable);
                    lines++;
                }
            }
        }
        if (lines == 0) throw new IllegalStateException("Nothing to pick: the orders have nothing allocated that is not picked already");
        auditService.log("PICK_LIST", listId, "CREATE", Map.of("orders", picked.stream().map(SaleOrder::getOrderNumber).toList(), "lines", lines));
        return pickList(listId);
    }

    private void insertLine(Long t, Long listId, SaleOrder so, SaleOrderLine l, Long binId, int walk, BigDecimal qty) {
        jdbc.update("""
                INSERT INTO pick_list_lines (tenant_id, pick_list_id, sale_order_id, sale_order_line_id, product_id, bin_id, walk_order, quantity)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", t, listId, so.getId(), l.getId(), l.getProduct().getId(), binId, walk, qty);
    }

    @Transactional(readOnly = true)
    public PickList pickList(Long id) {
        Long t = TenantContext.get();
        List<Map<String, Object>> h = jdbc.queryForList("""
                SELECT p.*, w.name AS warehouse FROM pick_lists p JOIN warehouses w ON w.id = p.warehouse_id WHERE p.tenant_id = ? AND p.id = ?""", t, id);
        if (h.isEmpty()) throw new IllegalArgumentException("Pick list not found");
        Map<String, Object> p = h.get(0);
        List<PickLine> lines = jdbc.query("""
                SELECT l.*, so.order_number, pr.sku, pr.name, pr.barcode, b.code AS bin FROM pick_list_lines l
                  JOIN sale_orders so ON so.id = l.sale_order_id JOIN products pr ON pr.id = l.product_id LEFT JOIN warehouse_bins b ON b.id = l.bin_id
                 WHERE l.pick_list_id = ? ORDER BY l.walk_order, b.code, pr.sku, so.order_number""", (rs, n) -> new PickLine(rs.getLong("id"),
                rs.getLong("sale_order_id"), rs.getString("order_number"), rs.getLong("sale_order_line_id"), rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("name"), rs.getString("barcode"), rs.getObject("bin_id") == null ? null : rs.getLong("bin_id"), rs.getString("bin"),
                rs.getInt("walk_order"), rs.getBigDecimal("quantity"), rs.getBigDecimal("picked")), id);
        return new PickList(id, "PL-" + id, ((Number) p.get("warehouse_id")).longValue(), (String) p.get("warehouse"), (String) p.get("status"),
                lines.stream().map(PickLine::orderNumber).distinct().toList(), lines, ((Timestamp) p.get("created_at")).toLocalDateTime(), (String) p.get("created_by"),
                p.get("picked_at") == null ? null : ((Timestamp) p.get("picked_at")).toLocalDateTime(), (String) p.get("picked_by"));
    }

    @Transactional(readOnly = true)
    public List<PickList> pickLists(String status) {
        return jdbc.queryForList("SELECT id FROM pick_lists WHERE tenant_id = ? AND (?::varchar IS NULL OR status = ?::varchar) ORDER BY created_at DESC LIMIT 100",
                Long.class, TenantContext.get(), status, status).stream().map(this::pickList).toList();
    }

    public record Picked(Long lineId, BigDecimal picked) {}

    /** The quantities picked (none given: everything on the list); the stock leaves its bins. Short picks may go on a new list. */
    @Transactional
    public PickList confirm(Long id, List<Picked> picked) {
        PickList pl = requireOpen(id);
        Map<Long, BigDecimal> given = new HashMap<>();
        if (picked != null) picked.forEach(p -> given.put(p.lineId(), p.picked()));
        // None given: what was scanned (N4) if anything was, else everything on the list
        boolean scanned = pl.lines().stream().anyMatch(l -> l.picked().signum() > 0);
        for (PickLine l : pl.lines()) {
            BigDecimal q = given.containsKey(l.id()) ? given.get(l.id()) : (given.isEmpty() && !scanned ? l.quantity() : l.picked());
            if (q == null || q.signum() < 0 || q.compareTo(l.quantity()) > 0) {
                throw new IllegalArgumentException(l.sku() + ": picked must be between 0 and " + l.quantity().stripTrailingZeros().toPlainString());
            }
            jdbc.update("UPDATE pick_list_lines SET picked = ? WHERE id = ?", q, l.id());
            if (l.binId() != null && q.signum() > 0) bins.post(l.productId(), pl.warehouseId(), l.binId(), q.negate(), "PICKED", pl.number() + " " + l.orderNumber());
        }
        jdbc.update("UPDATE pick_lists SET status = 'PICKED', picked_at = now(), picked_by = ? WHERE id = ?", auditService.currentActorName(), id);
        auditService.log("PICK_LIST", id, "PICKED", Map.of("orders", pl.orders()));
        return pickList(id);
    }

    /** Scanning (N4): the scanned code (barcode or SKU) counts one more (or qty) on the first line of that product still to pick. */
    @Transactional
    public PickList scan(Long id, String code, BigDecimal qty) {
        PickList pl = requireOpen(id);
        String c = code == null ? "" : code.trim();
        BigDecimal n = qty == null ? BigDecimal.ONE : qty;
        List<PickLine> matching = pl.lines().stream().filter(l -> c.equalsIgnoreCase(l.sku()) || (l.barcode() != null && c.equals(l.barcode()))).toList();
        if (matching.isEmpty()) throw new IllegalArgumentException("\"" + c + "\" is not on this pick list: wrong product");
        PickLine line = matching.stream().filter(l -> l.picked().compareTo(l.quantity()) < 0).findFirst()
                .orElseThrow(() -> new IllegalStateException(matching.get(0).sku() + " is picked in full already"));
        if (line.picked().add(n).compareTo(line.quantity()) > 0) throw new IllegalStateException("Only " + line.quantity().subtract(line.picked()).stripTrailingZeros().toPlainString()
                + " more " + line.sku() + " to pick here");
        jdbc.update("UPDATE pick_list_lines SET picked = picked + ? WHERE id = ?", n, line.id());
        return pickList(id);
    }

    @Transactional
    public void cancel(Long id) {
        requireOpen(id);
        jdbc.update("UPDATE pick_lists SET status = 'CANCELLED' WHERE id = ?", id);
        auditService.log("PICK_LIST", id, "CANCEL", Map.of());
    }

    public record PickPrint(String title, String subtitle, List<Map<String, String>> lines) {}

    @Transactional(readOnly = true)
    public DocumentService.Pdf pickListPdf(Long id) {
        PickList pl = pickList(id);
        List<Map<String, String>> lines = pl.lines().stream().map(l -> Map.of("bin", l.bin() == null ? "—" : l.bin(), "sku", l.sku(), "name", l.name(),
                "order", l.orderNumber(), "quantity", l.quantity().stripTrailingZeros().toPlainString())).toList();
        PickPrint p = new PickPrint("Pick list " + pl.number(), pl.warehouse() + " · " + pl.orders().size() + (pl.orders().size() == 1 ? " order" : " orders")
                + " · " + pl.createdAt().toLocalDate().format(UK), lines);
        return new DocumentService.Pdf(pl.number() + ".pdf", renderer.render("picklist", p, pl.number()));
    }

    private PickList requireOpen(Long id) {
        PickList pl = pickList(id);
        if (!"OPEN".equals(pl.status())) throw new IllegalStateException(pl.number() + " is " + pl.status().toLowerCase());
        return pl;
    }

    // ---- pack and ship (N3) ----

    public record PackLine(Long saleOrderLineId, BigDecimal quantity) {}

    public record Carton(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, List<PackLine> lines) {}

    public record Package(Long id, int number, BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm, String status,
                          Long fulfilmentId, List<Map<String, Object>> lines) {}

    public record OrderLineState(Long saleOrderLineId, String sku, String name, BigDecimal ordered, BigDecimal allocated, BigDecimal picked,
                                 BigDecimal packed, BigDecimal shipped) {}

    public record OrderWarehouse(Long saleOrderId, String orderNumber, String stage, List<OrderLineState> lines, List<Package> packages) {}

    /** Cartons for an order: no more of a line than is picked and not yet packed. None given: everything picked, in one carton. */
    @Transactional
    public OrderWarehouse pack(Long orderId, List<Carton> cartons) {
        SaleOrder so = orders.findByIdForUpdate(orderId).orElseThrow(() -> new IllegalArgumentException("Sale order not found"));
        if (so.getStatus() != SaleOrder.Status.CONFIRMED) throw new IllegalStateException(so.getOrderNumber() + " is not an open order");
        Map<Long, BigDecimal> packable = new HashMap<>();
        for (SaleOrderLine l : so.getLines()) packable.put(l.getId(), pickedTotal(l.getId()).subtract(packedTotal(l.getId())).max(BigDecimal.ZERO));
        List<Carton> list = cartons;
        if (list == null || list.isEmpty()) {
            List<PackLine> all = packable.entrySet().stream().filter(e -> e.getValue().signum() > 0).map(e -> new PackLine(e.getKey(), e.getValue())).toList();
            list = List.of(new Carton(null, null, null, null, all));
        }
        Long t = TenantContext.get();
        int number = Optional.ofNullable(jdbc.queryForObject("SELECT MAX(number) FROM packages WHERE tenant_id = ? AND sale_order_id = ?", Integer.class, t, orderId)).orElse(0);
        int made = 0;
        for (Carton c : list) {
            List<PackLine> lines = c.lines() == null ? List.of() : c.lines().stream().filter(l -> l.quantity() != null && l.quantity().signum() > 0).toList();
            if (lines.isEmpty()) continue;
            for (PackLine l : lines) {
                BigDecimal left = packable.get(l.saleOrderLineId());
                if (left == null) throw new IllegalArgumentException("Line " + l.saleOrderLineId() + " is not on " + so.getOrderNumber());
                if (l.quantity().compareTo(left) > 0) throw new IllegalStateException("Only " + left.stripTrailingZeros().toPlainString() + " picked and not packed on that line");
                packable.put(l.saleOrderLineId(), left.subtract(l.quantity()));
            }
            Long pkg = jdbc.queryForObject("""
                    INSERT INTO packages (tenant_id, sale_order_id, number, weight_kg, length_cm, width_cm, height_cm, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""",
                    Long.class, t, orderId, ++number, c.weightKg(), c.lengthCm(), c.widthCm(), c.heightCm(), auditService.currentActorName());
            for (PackLine l : lines) {
                jdbc.update("INSERT INTO package_lines (tenant_id, package_id, sale_order_line_id, quantity) VALUES (?, ?, ?, ?)", t, pkg, l.saleOrderLineId(), l.quantity());
            }
            made++;
        }
        if (made == 0) throw new IllegalStateException("Nothing to pack: pick the order first");
        auditService.log("SALE_ORDER", orderId, "PACKED", Map.of("cartons", made));
        return order(orderId);
    }

    /** Ships every packed carton of the order not shipped yet: one shipment, with the carrier and tracking number. */
    @Transactional
    public OrderWarehouse ship(Long orderId, String carrier, String trackingNumber) {
        Long t = TenantContext.get();
        List<Long> open = jdbc.queryForList("SELECT id FROM packages WHERE tenant_id = ? AND sale_order_id = ? AND status = 'PACKED' ORDER BY number", Long.class, t, orderId);
        if (open.isEmpty()) throw new IllegalStateException("No packed cartons to ship");
        Map<Long, BigDecimal> qty = new LinkedHashMap<>();
        jdbc.query("SELECT sale_order_line_id, SUM(quantity) FROM package_lines WHERE package_id = ANY(?::bigint[]) GROUP BY sale_order_line_id",
                rs -> { qty.put(rs.getLong(1), rs.getBigDecimal(2)); }, (Object) open.toArray(Long[]::new));
        Long[] cartons = open.toArray(Long[]::new);
        // Phase O: cartons booked with a courier ship with its tracking (unless other tracking is typed)
        String url = null;
        List<Map<String, Object>> booked = jdbc.queryForList("""
                SELECT carrier, service_name, tracking_number, tracking_url FROM courier_shipments WHERE tenant_id = ? AND sale_order_id = ? AND status = 'BOOKED'
                   AND id IN (SELECT courier_shipment_id FROM packages WHERE id = ANY(?::bigint[])) ORDER BY id DESC LIMIT 1""", t, orderId, cartons);
        if (!booked.isEmpty() && (carrier == null || carrier.isBlank()) && (trackingNumber == null || trackingNumber.isBlank())) {
            carrier = (String) booked.get(0).get("service_name");
            trackingNumber = (String) booked.get(0).get("tracking_number");
            url = (String) booked.get(0).get("tracking_url");
        }
        FulfilmentRequest req = new FulfilmentRequest();
        req.setTrackingCompany(carrier == null ? null : carrier.length() > 100 ? carrier.substring(0, 100) : carrier);
        req.setTrackingNumber(trackingNumber);
        req.setTrackingUrl(url);
        req.setNotes("Cartons " + open.size());
        req.setLines(qty.entrySet().stream().map(e -> {
            FulfilmentRequest.Line l = new FulfilmentRequest.Line();
            l.setSaleOrderLineId(e.getKey());
            l.setQuantity(e.getValue());
            return l;
        }).toList());
        saleOrderService.fulfill(orderId, req);
        List<Fulfilment> f = fulfilments.findBySaleOrderIdOrderByCreatedAtAsc(orderId);
        Long fid = f.isEmpty() ? null : f.get(f.size() - 1).getId();
        jdbc.update("UPDATE packages SET status = 'SHIPPED', fulfilment_id = ? WHERE id = ANY(?::bigint[])", fid, (Object) cartons);
        jdbc.update("""
                UPDATE courier_shipments SET status = 'SHIPPED', fulfilment_id = ? WHERE tenant_id = ? AND status = 'BOOKED'
                   AND id IN (SELECT courier_shipment_id FROM packages WHERE id = ANY(?::bigint[]))""", fid, t, cartons);
        return order(orderId);
    }

    /** The order's latest shipment (fulfilment) id. */
    @Transactional(readOnly = true)
    public Long lastShipment(Long orderId) {
        List<Fulfilment> f = fulfilments.findBySaleOrderIdOrderByCreatedAtAsc(orderId);
        if (f.isEmpty()) throw new IllegalStateException("Nothing shipped yet");
        return f.get(f.size() - 1).getId();
    }

    public record DespatchPrint(String title, String company, List<String> deliverTo, String reference, List<Map<String, Object>> packages) {}

    @Transactional(readOnly = true)
    public DocumentService.Pdf despatchNote(Long orderId) {
        SaleOrder so = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Sale order not found"));
        OrderWarehouse w = order(orderId);
        if (w.packages().isEmpty()) throw new IllegalStateException("Nothing packed yet");
        List<String> to = new ArrayList<>();
        to.add(so.getShipToCompany() != null ? so.getShipToCompany() : so.getCustomerName());
        if (so.getShipToContact() != null) to.add(so.getShipToContact());
        if (so.getShippingAddress() != null) to.addAll(so.getShippingAddress().lines());
        int total = w.packages().size();
        List<Map<String, Object>> pk = w.packages().stream().map(p -> {
            String size = p.lengthCm() == null ? "" : " · " + p.lengthCm().stripTrailingZeros().toPlainString() + " × " + p.widthCm().stripTrailingZeros().toPlainString()
                    + " × " + p.heightCm().stripTrailingZeros().toPlainString() + " cm";
            String title = "Carton " + p.number() + " of " + total + (p.weightKg() == null ? "" : " · " + p.weightKg().stripTrailingZeros().toPlainString() + " kg") + size;
            return Map.<String, Object>of("title", title, "lines", p.lines());
        }).toList();
        DespatchPrint d = new DespatchPrint("Despatch note " + so.getOrderNumber(), documents.company().name(), to, so.getCustomerReference(), pk);
        return new DocumentService.Pdf("Despatch-" + so.getOrderNumber() + ".pdf", renderer.render("despatch", d, so.getOrderNumber()));
    }

    /** The order's lines (ordered, allocated, picked, packed, shipped), its cartons and its warehouse stage. */
    @Transactional(readOnly = true)
    public OrderWarehouse order(Long orderId) {
        SaleOrder so = orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Sale order not found"));
        Map<Long, AllocationService.LineAllocation> alloc = allocation.forOrders(List.of(orderId));
        List<OrderLineState> lines = new ArrayList<>();
        for (SaleOrderLine l : so.getLines()) {
            AllocationService.LineAllocation a = alloc.get(l.getId());
            lines.add(new OrderLineState(l.getId(), l.getProduct().getSku(), l.getProduct().getName(), l.getQuantity().subtract(l.getCancelledQuantity()),
                    a == null ? BigDecimal.ZERO : a.allocated(), pickedTotal(l.getId()), packedTotal(l.getId()), l.getFulfilledQuantity()));
        }
        List<Package> packages = jdbc.query("SELECT * FROM packages WHERE tenant_id = ? AND sale_order_id = ? ORDER BY number", (rs, n) -> new Package(rs.getLong("id"),
                rs.getInt("number"), rs.getBigDecimal("weight_kg"), rs.getBigDecimal("length_cm"), rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm"),
                rs.getString("status"), rs.getObject("fulfilment_id") == null ? null : rs.getLong("fulfilment_id"),
                jdbc.queryForList("""
                        SELECT pl.sale_order_line_id, pr.sku, pr.name, pl.quantity FROM package_lines pl JOIN sale_order_lines sl ON sl.id = pl.sale_order_line_id
                          JOIN products pr ON pr.id = sl.product_id WHERE pl.package_id = ? ORDER BY pl.id""", rs.getLong("id"))), TenantContext.get(), orderId);
        return new OrderWarehouse(orderId, so.getOrderNumber(), stage(so, lines), lines, packages);
    }

    /** Authorised → Picking → Picked → Packed → Shipped (what is left to ship decides). */
    private String stage(SaleOrder so, List<OrderLineState> lines) {
        if (so.getStatus() == SaleOrder.Status.COMPLETED || so.getFulfilmentStatus() == SaleOrder.FulfilmentStatus.FULFILLED) return "SHIPPED";
        if (so.getStatus() != SaleOrder.Status.CONFIRMED) return so.getStatus().name();
        Boolean picking = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pick_list_lines l JOIN pick_lists p ON p.id = l.pick_list_id WHERE p.tenant_id = ? AND p.status = 'OPEN' AND l.sale_order_id = ?)""",
                Boolean.class, TenantContext.get(), so.getId());
        if (Boolean.TRUE.equals(picking)) return "PICKING";
        boolean anyLeft = false, allPicked = true, allPacked = true;
        for (OrderLineState l : lines) {
            BigDecimal left = l.ordered().subtract(l.shipped());
            if (left.signum() <= 0) continue;
            anyLeft = true;
            if (l.picked().subtract(l.shipped()).compareTo(left) < 0) allPicked = false;
            if (l.packed().subtract(l.shipped()).compareTo(left) < 0) allPacked = false;
        }
        if (!anyLeft) return "SHIPPED";
        if (allPacked) return "PACKED";
        if (allPicked) return "PICKED";
        return "AUTHORISED";
    }

    // ---- shared ----

    private BigDecimal pickedTotal(Long lineId) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT SUM(l.picked) FROM pick_list_lines l JOIN pick_lists p ON p.id = l.pick_list_id WHERE p.status = 'PICKED' AND l.sale_order_line_id = ?""",
                BigDecimal.class, lineId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal packedTotal(Long lineId) {
        BigDecimal v = jdbc.queryForObject("SELECT SUM(quantity) FROM package_lines WHERE sale_order_line_id = ?", BigDecimal.class, lineId);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal pickedNotShipped(SaleOrderLine l) {
        return pickedTotal(l.getId()).subtract(l.getFulfilledQuantity()).max(BigDecimal.ZERO);
    }

    private BigDecimal onOpenLists(Long lineId) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT SUM(l.quantity) FROM pick_list_lines l JOIN pick_lists p ON p.id = l.pick_list_id WHERE p.status = 'OPEN' AND l.sale_order_line_id = ?""",
                BigDecimal.class, lineId);
        return v == null ? BigDecimal.ZERO : v;
    }
}
