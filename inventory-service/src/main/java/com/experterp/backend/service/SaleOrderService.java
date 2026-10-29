package com.experterp.backend.service;

import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.dto.SaleReturnRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.FulfilmentRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.SaleReturnRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sale order lifecycle:
 * <pre>
 *   DRAFT ──confirm──▶ CONFIRMED ──fulfil (one or more shipments)──▶ COMPLETED
 *     │                    │
 *     └──────cancel────────┴──▶ CANCELLED            (nothing shipped yet)
 *                          └──cancel──▶ COMPLETED    (remaining quantities cancelled, shipped ones kept)
 * </pre>
 * Returns can be recorded against any shipped quantity of a CONFIRMED or COMPLETED order.
 * All state changes lock the order row first, so concurrent requests cannot ship or return the same quantity twice.
 */
@Service
@RequiredArgsConstructor
public class SaleOrderService {

    private final SaleOrderRepository soRepository;
    private final FulfilmentRepository fulfilmentRepository;
    private final SaleReturnRepository returnRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductService productService;
    private final StockMovementService movementService;
    private final AuditService auditService;

    public Page<SaleOrderResponse> findAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return soRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public SaleOrderResponse findById(Long id) {
        return toDetailResponse(getSO(id));
    }

    @Transactional
    public SaleOrderResponse create(SaleOrderRequest req) {
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));

        SaleOrder so = new SaleOrder();
        so.setCustomerName(req.getCustomerName());
        so.setWarehouse(warehouse);
        so.setNotes(req.getNotes());

        for (SaleOrderRequest.Line l : req.getLines()) {
            Product product = productService.getProduct(l.getProductId());
            SaleOrderLine line = new SaleOrderLine();
            line.setSaleOrder(so);
            line.setProduct(product);
            line.setQuantity(l.getQuantity());
            line.setUnitPrice(l.getUnitPrice());
            so.getLines().add(line);
        }

        SaleOrderResponse saved = toResponse(soRepository.save(so));
        auditService.log("SALE_ORDER", saved.getId(), "CREATE",
                Map.of("customer", so.getCustomerName(), "lines", so.getLines().size()));
        return saved;
    }

    @Transactional
    public SaleOrderResponse confirm(Long id) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be confirmed (order is " + so.getStatus() + ")");
        }
        so.setStatus(Status.CONFIRMED);
        so.setConfirmedAt(LocalDateTime.now());
        auditService.log("SALE_ORDER", id, "CONFIRM", Map.of("status", Status.CONFIRMED));
        return toDetailResponse(so);
    }

    /** Ships the requested quantities (or everything remaining) and creates one SALE_OUT movement per line. */
    @Transactional
    public SaleOrderResponse fulfill(Long id, FulfilmentRequest req) {
        SaleOrder so = lockSO(id);

        String key = req != null ? blankToNull(req.getIdempotencyKey()) : null;
        if (key != null) {
            Optional<Fulfilment> previous = fulfilmentRepository.findByIdempotencyKey(key);
            if (previous.isPresent()) {
                assertSameOrder(previous.get().getSaleOrder(), id, key);
                return toDetailResponse(so);
            }
        }

        if (so.getStatus() != Status.CONFIRMED) {
            throw new IllegalStateException("Only CONFIRMED orders can be fulfilled (order is " + so.getStatus() + ")");
        }

        List<LineQty> toShip = resolveFulfilmentLines(so, req);

        // Check stock for all lines up front (summed per product) so the error names the real shortfall
        Map<Long, BigDecimal> perProduct = new HashMap<>();
        toShip.forEach(s -> perProduct.merge(s.line().getProduct().getId(), s.qty(), BigDecimal::add));
        perProduct.forEach((productId, qty) ->
                movementService.assertSufficientStock(productId, so.getWarehouse().getId(), qty));

        Fulfilment f = new Fulfilment();
        f.setSaleOrder(so);
        f.setIdempotencyKey(key);
        f.setNotes(req != null ? req.getNotes() : null);
        f.setCreatedBy(auditService.currentActorName());
        toShip.forEach(s -> {
            FulfilmentLine fl = new FulfilmentLine();
            fl.setFulfilment(f);
            fl.setSaleOrderLine(s.line());
            fl.setQuantity(s.qty());
            f.getLines().add(fl);
        });
        fulfilmentRepository.save(f);

        toShip.forEach(s -> {
            movementService.createMovement(
                    s.line().getProduct(), so.getWarehouse(), s.qty(),
                    MovementType.SALE_OUT, ReferenceType.SALE_ORDER,
                    "SO-" + so.getId(), "Fulfilment #" + f.getId() + " on SO #" + so.getId());
            s.line().setFulfilledQuantity(s.line().getFulfilledQuantity().add(s.qty()));
        });

        so.refreshFulfilmentStatus();
        if (so.getFulfilmentStatus() == SaleOrder.FulfilmentStatus.FULFILLED) {
            so.setFulfilledAt(LocalDateTime.now());
        }
        if (!so.hasRemaining()) {
            so.setStatus(Status.COMPLETED);
        }

        auditService.log("SALE_ORDER", id, "FULFILL", Map.of(
                "fulfilmentId", f.getId(),
                "lines", describe(toShip),
                "fulfilmentStatus", so.getFulfilmentStatus()));
        return toDetailResponse(so);
    }

    /**
     * Nothing shipped yet → order becomes CANCELLED.
     * Partly shipped → remaining quantities are cancelled and the order becomes COMPLETED;
     * shipped quantities stay as they are (use a return if goods come back).
     */
    @Transactional
    public SaleOrderResponse cancel(Long id) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.DRAFT && so.getStatus() != Status.CONFIRMED) {
            throw new IllegalStateException("Only DRAFT or CONFIRMED orders can be cancelled (order is " + so.getStatus() + ")");
        }

        List<LineQty> cancelled = new ArrayList<>();
        for (SaleOrderLine line : so.getLines()) {
            BigDecimal remaining = line.getRemainingQuantity();
            if (remaining.signum() > 0) {
                line.setCancelledQuantity(line.getCancelledQuantity().add(remaining));
                cancelled.add(new LineQty(line, remaining));
            }
        }

        String action;
        if (so.hasFulfilledAnything()) {
            so.setStatus(Status.COMPLETED);
            action = "CANCEL_REMAINING";
        } else {
            so.setStatus(Status.CANCELLED);
            action = "CANCEL";
        }
        so.setCancelledAt(LocalDateTime.now());

        auditService.log("SALE_ORDER", id, action, Map.of(
                "status", so.getStatus(),
                "cancelledLines", describe(cancelled)));
        return toDetailResponse(so);
    }

    /** Records goods coming back; restocked lines create SALE_RETURN_IN movements. */
    @Transactional
    public SaleOrderResponse createReturn(Long id, SaleReturnRequest req) {
        SaleOrder so = lockSO(id);

        String key = blankToNull(req.getIdempotencyKey());
        if (key != null) {
            Optional<SaleReturn> previous = returnRepository.findByIdempotencyKey(key);
            if (previous.isPresent()) {
                assertSameOrder(previous.get().getSaleOrder(), id, key);
                return toDetailResponse(so);
            }
        }

        if (so.getStatus() != Status.CONFIRMED && so.getStatus() != Status.COMPLETED) {
            throw new IllegalStateException("Returns can only be recorded on CONFIRMED or COMPLETED orders (order is " + so.getStatus() + ")");
        }

        Warehouse warehouse = req.getWarehouseId() == null ? so.getWarehouse()
                : warehouseRepository.findById(req.getWarehouseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));

        Map<Long, SaleOrderLine> linesById = linesById(so);
        Set<Long> seen = new HashSet<>();
        SaleReturn r = new SaleReturn();
        r.setSaleOrder(so);
        r.setWarehouse(warehouse);
        r.setIdempotencyKey(key);
        r.setReason(req.getReason());
        r.setCreatedBy(auditService.currentActorName());

        for (SaleReturnRequest.Line l : req.getLines()) {
            SaleOrderLine line = requireLine(linesById, l.getSaleOrderLineId(), id);
            if (!seen.add(line.getId())) {
                throw new IllegalArgumentException("Line " + line.getId() + " appears more than once");
            }
            if (l.getQuantity().compareTo(line.getReturnableQuantity()) > 0) {
                throw new IllegalStateException(String.format(
                        "Cannot return %s of %s: only %s shipped and not yet returned",
                        l.getQuantity().stripTrailingZeros().toPlainString(), line.getProduct().getSku(),
                        line.getReturnableQuantity().stripTrailingZeros().toPlainString()));
            }
            SaleReturnLine rl = new SaleReturnLine();
            rl.setSaleReturn(r);
            rl.setSaleOrderLine(line);
            rl.setQuantity(l.getQuantity());
            rl.setRestock(l.isRestock());
            r.getLines().add(rl);
        }
        returnRepository.save(r);

        for (SaleReturnLine rl : r.getLines()) {
            SaleOrderLine line = rl.getSaleOrderLine();
            line.setReturnedQuantity(line.getReturnedQuantity().add(rl.getQuantity()));
            if (rl.isRestock()) {
                movementService.createMovement(
                        line.getProduct(), warehouse, rl.getQuantity(),
                        MovementType.SALE_RETURN_IN, ReferenceType.SALE_RETURN,
                        "RET-" + r.getId(), "Return #" + r.getId() + " on SO #" + so.getId());
            }
        }

        auditService.log("SALE_ORDER", id, "RETURN", Map.of(
                "returnId", r.getId(),
                "warehouse", warehouse.getName(),
                "lines", r.getLines().stream().map(rl -> Map.of(
                        "sku", rl.getSaleOrderLine().getProduct().getSku(),
                        "qty", rl.getQuantity(),
                        "restock", rl.isRestock())).toList()));
        return toDetailResponse(so);
    }

    // ---- private ----

    // Entities use Lombok @Data (hashCode walks the order<->line graph), so never use them as map keys
    private record LineQty(SaleOrderLine line, BigDecimal qty) {}

    private List<LineQty> resolveFulfilmentLines(SaleOrder so, FulfilmentRequest req) {
        List<LineQty> toShip = new ArrayList<>();

        if (req == null || req.getLines() == null || req.getLines().isEmpty()) {
            for (SaleOrderLine line : so.getLines()) {
                if (line.getRemainingQuantity().signum() > 0) {
                    toShip.add(new LineQty(line, line.getRemainingQuantity()));
                }
            }
            if (toShip.isEmpty()) {
                throw new IllegalStateException("Nothing left to fulfil on SO #" + so.getId());
            }
            return toShip;
        }

        Map<Long, SaleOrderLine> linesById = linesById(so);
        Set<Long> seen = new HashSet<>();
        for (FulfilmentRequest.Line l : req.getLines()) {
            SaleOrderLine line = requireLine(linesById, l.getSaleOrderLineId(), so.getId());
            if (!seen.add(line.getId())) {
                throw new IllegalArgumentException("Line " + line.getId() + " appears more than once");
            }
            if (l.getQuantity().compareTo(line.getRemainingQuantity()) > 0) {
                throw new IllegalStateException(String.format(
                        "Cannot fulfil %s of %s: only %s remaining",
                        l.getQuantity().stripTrailingZeros().toPlainString(), line.getProduct().getSku(),
                        line.getRemainingQuantity().stripTrailingZeros().toPlainString()));
            }
            toShip.add(new LineQty(line, l.getQuantity()));
        }
        return toShip;
    }

    private Map<Long, SaleOrderLine> linesById(SaleOrder so) {
        return so.getLines().stream().collect(Collectors.toMap(SaleOrderLine::getId, l -> l));
    }

    private SaleOrderLine requireLine(Map<Long, SaleOrderLine> linesById, Long lineId, Long orderId) {
        SaleOrderLine line = linesById.get(lineId);
        if (line == null) {
            throw new ResourceNotFoundException("Line " + lineId + " does not belong to SO #" + orderId);
        }
        return line;
    }

    private void assertSameOrder(SaleOrder previousOrder, Long orderId, String key) {
        if (!previousOrder.getId().equals(orderId)) {
            throw new IllegalStateException("Idempotency key '" + key + "' was already used for SO #" + previousOrder.getId());
        }
    }

    private List<Map<String, Object>> describe(List<LineQty> lines) {
        return lines.stream()
                .map(s -> Map.<String, Object>of("sku", s.line().getProduct().getSku(), "qty", s.qty()))
                .toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private SaleOrder getSO(Long id) {
        return soRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale order not found: " + id));
    }

    private SaleOrder lockSO(Long id) {
        return soRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale order not found: " + id));
    }

    private SaleOrderResponse toDetailResponse(SaleOrder so) {
        SaleOrderResponse r = toResponse(so);
        r.setFulfilments(fulfilmentRepository.findBySaleOrderIdOrderByCreatedAtAsc(so.getId()).stream().map(f -> {
            SaleOrderResponse.FulfilmentEntry e = new SaleOrderResponse.FulfilmentEntry();
            e.setId(f.getId());
            e.setNotes(f.getNotes());
            e.setCreatedBy(f.getCreatedBy());
            e.setCreatedAt(f.getCreatedAt());
            e.setLines(f.getLines().stream()
                    .map(fl -> entryLine(fl.getSaleOrderLine(), fl.getQuantity(), null)).toList());
            return e;
        }).toList());
        r.setReturns(returnRepository.findBySaleOrderIdOrderByCreatedAtAsc(so.getId()).stream().map(ret -> {
            SaleOrderResponse.ReturnEntry e = new SaleOrderResponse.ReturnEntry();
            e.setId(ret.getId());
            e.setReason(ret.getReason());
            e.setWarehouseId(ret.getWarehouse().getId());
            e.setWarehouseName(ret.getWarehouse().getName());
            e.setCreatedBy(ret.getCreatedBy());
            e.setCreatedAt(ret.getCreatedAt());
            e.setLines(ret.getLines().stream()
                    .map(rl -> entryLine(rl.getSaleOrderLine(), rl.getQuantity(), rl.isRestock())).toList());
            return e;
        }).toList());
        return r;
    }

    private SaleOrderResponse.EntryLine entryLine(SaleOrderLine line, BigDecimal qty, Boolean restock) {
        SaleOrderResponse.EntryLine el = new SaleOrderResponse.EntryLine();
        el.setSaleOrderLineId(line.getId());
        el.setProductSku(line.getProduct().getSku());
        el.setProductName(line.getProduct().getName());
        el.setQuantity(qty);
        el.setRestock(restock);
        return el;
    }

    private SaleOrderResponse toResponse(SaleOrder so) {
        SaleOrderResponse r = new SaleOrderResponse();
        r.setId(so.getId());
        r.setCustomerName(so.getCustomerName());
        r.setWarehouseId(so.getWarehouse().getId());
        r.setWarehouseName(so.getWarehouse().getName());
        r.setStatus(so.getStatus());
        r.setFulfilmentStatus(so.getFulfilmentStatus());
        r.setNotes(so.getNotes());
        r.setCreatedAt(so.getCreatedAt());
        r.setConfirmedAt(so.getConfirmedAt());
        r.setFulfilledAt(so.getFulfilledAt());
        r.setCancelledAt(so.getCancelledAt());
        r.setLines(so.getLines().stream().map(l -> {
            SaleOrderResponse.Line lr = new SaleOrderResponse.Line();
            lr.setId(l.getId());
            lr.setProductId(l.getProduct().getId());
            lr.setProductSku(l.getProduct().getSku());
            lr.setProductName(l.getProduct().getName());
            lr.setQuantity(l.getQuantity());
            lr.setUnitPrice(l.getUnitPrice());
            lr.setFulfilledQuantity(l.getFulfilledQuantity());
            lr.setCancelledQuantity(l.getCancelledQuantity());
            lr.setReturnedQuantity(l.getReturnedQuantity());
            lr.setRemainingQuantity(l.getRemainingQuantity());
            return lr;
        }).collect(Collectors.toList()));
        return r;
    }
}
