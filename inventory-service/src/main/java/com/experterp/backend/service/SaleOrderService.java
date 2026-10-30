package com.experterp.backend.service;

import com.experterp.backend.dto.FulfilmentRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.dto.SaleReturnRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.event.FulfilmentCreatedEvent;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.FulfilmentRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.SaleReturnRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    private final CustomerService customerService;
    private final StockMovementService movementService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;
    private final TaxService taxService;
    private final OrganisationSettingsService settingsService;
    private final AllocationService allocationService;
    private final InvoiceService invoiceService;

    // May choose a different VAT rate than the rule gives (docs/business-rules.md §3)

    @Transactional(readOnly = true)
    public Page<SaleOrderResponse> findAll(Long customerId, Status status, int page, int size) {
        return findAll(customerId, status, null, page, size);
    }

    @Transactional(readOnly = true)
    public Page<SaleOrderResponse> findAll(Long customerId, Status status, String q, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<SaleOrder> orders = soRepository.search(customerId, status, Search.pattern(q), pageable);
        // One allocation query for the whole page
        List<Long> ids = orders.getContent().stream().map(SaleOrder::getId).toList();
        Map<Long, AllocationService.LineAllocation> allocations = allocationService.forOrders(ids);
        // Documents column: the page's invoices and credit notes in one query
        Map<Long, List<InvoiceResponse>> invoicesByOrder = invoiceService.forOrders(ids);
        return orders.map(so -> {
            SaleOrderResponse r = toResponse(so, allocations);
            setInvoices(r, invoicesByOrder.getOrDefault(so.getId(), List.of()).stream().map(this::invoiceEntry).toList());
            return r;
        });
    }

    @Transactional(readOnly = true)
    public SaleOrderResponse findById(Long id) {
        return toDetailResponse(getSO(id));
    }

    @Transactional
    public SaleOrderResponse create(SaleOrderRequest req) {
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));

        Customer customer = customerService.resolveForOrder(req.getCustomerId(), req.getCustomerName());

        List<Map<String, Object>> taxOverrides = new ArrayList<>();
        SaleOrder so = build(req, warehouse, customer, taxOverrides);
        so.setOrderNumber(settingsService.nextSaleOrderNumber());
        if (req.isQuote()) {
            so.setStatus(Status.QUOTE);
            so.setQuoteValidUntil(req.getQuoteValidUntil() != null ? req.getQuoteValidUntil()
                    : LocalDate.now().plusDays(settingsService.quoteValidDays()));
        } else {
            so.setOrderedAt(LocalDateTime.now());
        }

        SaleOrderResponse saved = toResponse(soRepository.save(so));
        Map<String, Object> audit = new HashMap<>(Map.of("orderNumber", so.getOrderNumber(), "status", so.getStatus(), "customer", so.getCustomerName(),
                "customerId", customer.getId(), "lines", so.getLines().size(), "total", saved.getTotal()));
        if (!so.getCharges().isEmpty()) audit.put("charges", so.getCharges().size());
        if (!taxOverrides.isEmpty()) audit.put("taxOverrides", taxOverrides);
        auditService.log("SALE_ORDER", saved.getId(), "CREATE", audit);
        return saved;
    }

    /**
     * The order as it would be created — rep, terms, discounts, VAT per line, totals — without saving anything,
     * so the form can show the figures while it is filled in. Lines without a product or quantity are left out;
     * a customer given only by a new name is not created.
     */
    @Transactional
    public SaleOrderResponse preview(SaleOrderRequest req) {
        Warehouse warehouse = req.getWarehouseId() == null ? null : warehouseRepository.findById(req.getWarehouseId()).orElse(null);
        Customer customer = req.getCustomerId() != null ? customerService.getCustomer(req.getCustomerId())
                : customerService.findByName(req.getCustomerName()).orElse(null);
        SaleOrderRequest complete = new SaleOrderRequest();
        org.springframework.beans.BeanUtils.copyProperties(req, complete);
        complete.setLines(req.getLines() == null ? List.of() : req.getLines().stream()
                .filter(l -> l.getProductId() != null && l.getQuantity() != null && l.getQuantity().signum() > 0).toList());
        complete.setCharges(req.getCharges() == null ? List.of() : req.getCharges().stream()
                .filter(c -> c.getDescription() != null && !c.getDescription().isBlank() && c.getUnitPrice() != null).toList());
        SaleOrder so = build(complete, warehouse, customer, new ArrayList<>());
        if (customer == null) so.setCustomerName(req.getCustomerName());
        return toResponse(so);
    }

    /**
     * Replaces a quote's or draft order's header, lines and charges; VAT and defaults are worked out again as for
     * a new order. Shopify drafts are rebuilt from Shopify instead, and nothing is edited once confirmed.
     */
    @Transactional
    public SaleOrderResponse update(Long id, SaleOrderRequest req) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.QUOTE && so.getStatus() != Status.DRAFT) {
            throw new IllegalStateException("Only quotes and draft orders can be changed (" + so.getOrderNumber() + " is " + so.getStatus() + ")");
        }
        if (so.getSource() != SaleOrder.Source.MANUAL) {
            throw new IllegalStateException(so.getOrderNumber() + " comes from Shopify; change it there");
        }
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));
        Customer customer = customerService.resolveForOrder(req.getCustomerId(), req.getCustomerName());

        List<Map<String, Object>> taxOverrides = new ArrayList<>();
        SaleOrder built = build(req, warehouse, customer, taxOverrides);
        so.setCustomer(built.getCustomer());
        so.setCustomerName(built.getCustomerName());
        so.setWarehouse(warehouse);
        so.setNotes(built.getNotes());
        so.setCustomerReference(built.getCustomerReference());
        so.setSalesRep(built.getSalesRep());
        so.setPaymentTerm(built.getPaymentTerm());
        so.setShipBy(built.getShipBy());
        so.setCarrier(built.getCarrier());
        so.setShippingNotes(built.getShippingNotes());
        if (so.getStatus() == Status.QUOTE && req.getQuoteValidUntil() != null) so.setQuoteValidUntil(req.getQuoteValidUntil());
        so.getLines().clear();
        built.getLines().forEach(l -> { l.setSaleOrder(so); so.getLines().add(l); });
        so.getCharges().clear();
        built.getCharges().forEach(c -> { c.setSaleOrder(so); so.getCharges().add(c); });

        SaleOrderResponse saved = toDetailResponse(soRepository.save(so));
        Map<String, Object> audit = new HashMap<>(Map.of("orderNumber", so.getOrderNumber(), "customer", so.getCustomerName(),
                "lines", so.getLines().size(), "total", saved.getTotal()));
        if (so.getQuoteValidUntil() != null) audit.put("quoteValidUntil", so.getQuoteValidUntil().toString());
        if (!taxOverrides.isEmpty()) audit.put("taxOverrides", taxOverrides);
        auditService.log("SALE_ORDER", id, "UPDATE", audit);
        return saved;
    }

    /** A quote becomes the (draft) order, same number and lines; an expired quote must be renewed first. */
    @Transactional
    public SaleOrderResponse convertQuote(Long id) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.QUOTE) {
            throw new IllegalStateException("Only quotes can be converted (" + so.getOrderNumber() + " is " + so.getStatus() + ")");
        }
        if (isExpired(so)) {
            throw new IllegalStateException("Quote " + so.getOrderNumber() + " expired on " + so.getQuoteValidUntil()
                    + "; give it a new valid-until date to renew it first");
        }
        so.setStatus(Status.DRAFT);
        so.setOrderedAt(LocalDateTime.now());
        auditService.log("SALE_ORDER", id, "CONVERT", Map.of("orderNumber", so.getOrderNumber(), "status", Status.DRAFT));
        return toDetailResponse(so);
    }

    @Transactional
    public SaleOrderResponse rejectQuote(Long id) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.QUOTE) {
            throw new IllegalStateException("Only quotes can be rejected (" + so.getOrderNumber() + " is " + so.getStatus() + ")");
        }
        so.setStatus(Status.REJECTED);
        so.setCancelledAt(LocalDateTime.now());
        auditService.log("SALE_ORDER", id, "REJECT", Map.of("orderNumber", so.getOrderNumber(), "status", Status.REJECTED));
        return toDetailResponse(so);
    }

    private static boolean isExpired(SaleOrder so) {
        return so.getStatus() == Status.QUOTE && so.getQuoteValidUntil() != null
                && so.getQuoteValidUntil().isBefore(LocalDate.now());
    }

    @Transactional
    public SaleOrderResponse confirm(Long id) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be confirmed (order is " + so.getStatus() + ")");
        }
        so.setStatus(Status.CONFIRMED);
        so.setConfirmedAt(LocalDateTime.now());
        Map<String, Object> changes = new HashMap<>(Map.of("status", Status.CONFIRMED));
        if (so.isNeedsReview()) {
            // Confirming an imported order that needed review means a person accepted it as it is
            changes.put("reviewed", so.getReviewReason() == null ? "" : so.getReviewReason());
            so.setNeedsReview(false);
        }
        auditService.log("SALE_ORDER", id, "CONFIRM", changes);
        return toDetailResponse(so);
    }

    /**
     * Ships the requested quantities (or everything allocated) and creates one SALE_OUT movement per line.
     * Like Cin7, a line can only ship what is allocated to it — stock promised to orders confirmed earlier is
     * not taken (first confirmed, first served). The products are locked first, so shipments of other orders
     * running at the same time are counted before the allocation is worked out.
     */
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

        movementService.lockProducts(so.getLines().stream().map(l -> l.getProduct().getId()).toList());
        Map<Long, AllocationService.LineAllocation> allocations = allocationService.forOrders(List.of(so.getId()));
        List<LineQty> toShip = resolveFulfilmentLines(so, req, allocations);

        // Check stock for all lines up front (summed per product) so the error names the real shortfall
        Map<Long, BigDecimal> perProduct = new HashMap<>();
        toShip.forEach(s -> perProduct.merge(s.line().getProduct().getId(), s.qty(), BigDecimal::add));
        perProduct.forEach((productId, qty) ->
                movementService.assertSufficientStock(productId, so.getWarehouse().getId(), qty));

        Fulfilment f = new Fulfilment();
        f.setSaleOrder(so);
        f.setIdempotencyKey(key);
        f.setNotes(req != null ? req.getNotes() : null);
        f.setTrackingCompany(req != null ? blankToNull(req.getTrackingCompany()) : null);
        f.setTrackingNumber(req != null ? blankToNull(req.getTrackingNumber()) : null);
        f.setCreatedBy(auditService.currentActorName());
        // Shopify orders: the shipment is sent to Shopify once this transaction has committed
        if (so.getSource() == SaleOrder.Source.SHOPIFY) f.setExternalSyncStatus(Fulfilment.SyncStatus.PENDING);
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

        // Organisation setting: each shipment is invoiced (what it shipped and is not invoiced yet, plus open charges)
        if (settingsService.autoInvoiceOnShipment()) {
            Map<Long, BigDecimal> shipped = new HashMap<>();
            toShip.forEach(s -> shipped.merge(s.line().getId(), s.qty().min(s.line().getToInvoiceQuantity()), BigDecimal::add));
            invoiceService.invoiceFor(so, shipped, true, true, f.getId(), "Invoiced on shipment #" + f.getId());
        }
        if (f.getExternalSyncStatus() != null) {
            eventPublisher.publishEvent(new FulfilmentCreatedEvent(TenantContext.get(), so.getId(), f.getId()));
        }
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

    private record TaxChoice(TaxRate rate, boolean overridden) {}

    /** The order from the request, not saved: header defaults from the customer, discount and VAT on every line. */
    private SaleOrder build(SaleOrderRequest req, Warehouse warehouse, Customer customer, List<Map<String, Object>> taxOverrides) {
        SaleOrder so = new SaleOrder();
        so.setCustomer(customer);
        so.setCustomerName(customer != null ? customer.getName() : null);
        so.setWarehouse(warehouse);
        so.setNotes(req.getNotes());
        so.setCustomerReference(blankToNull(req.getCustomerReference()));
        so.setSalesRep(orElse(req.getSalesRep(), customer != null ? customer.getSalesRepresentative() : null));
        so.setPaymentTerm(orElse(req.getPaymentTerm(), customer != null ? customer.getPaymentTerm() : null));
        so.setShipBy(req.getShipBy());
        so.setCarrier(orElse(req.getCarrier(), customer != null ? customer.getCarrier() : null));
        so.setShippingNotes(blankToNull(req.getShippingNotes()));
        BigDecimal customerDiscount = customer != null && customer.getDiscount() != null ? customer.getDiscount() : BigDecimal.ZERO;

        for (SaleOrderRequest.Line l : req.getLines()) {
            Product product = productService.getProduct(l.getProductId());
            SaleOrderLine line = new SaleOrderLine();
            line.setSaleOrder(so);
            line.setProduct(product);
            line.setQuantity(l.getQuantity());
            line.setUnitPrice(l.getUnitPrice());
            line.setDiscountPercent(l.getDiscountPercent() != null ? l.getDiscountPercent() : customerDiscount);
            TaxChoice tax = chooseTax(l.getTaxRule(), l.getTaxReason(), taxService.resolveSale(product, customer),
                    product.getSku(), taxOverrides);
            line.setTaxName(tax.rate().getName());
            line.setTaxRate(tax.rate().getRate());
            line.setTaxOverridden(tax.overridden());
            so.getLines().add(line);
        }

        if (req.getCharges() != null) {
            for (SaleOrderRequest.Charge c : req.getCharges()) {
                SaleOrderCharge charge = new SaleOrderCharge();
                charge.setSaleOrder(so);
                charge.setDescription(c.getDescription().trim());
                charge.setQuantity(c.getQuantity() != null ? c.getQuantity() : BigDecimal.ONE);
                charge.setUnitPrice(c.getUnitPrice());
                charge.setDiscountPercent(c.getDiscountPercent() != null ? c.getDiscountPercent() : BigDecimal.ZERO);
                TaxChoice tax = chooseTax(c.getTaxRule(), c.getTaxReason(), taxService.resolveSale(null, customer),
                        charge.getDescription(), taxOverrides);
                charge.setTaxName(tax.rate().getName());
                charge.setTaxRate(tax.rate().getRate());
                charge.setTaxOverridden(tax.overridden());
                so.getCharges().add(charge);
            }
        }
        return so;
    }

    private TaxChoice chooseTax(String requested, String reason, TaxRate rule, String what, List<Map<String, Object>> overrides) {
        TaxService.Choice c = taxService.choose(TaxRate.Usage.SALE, requested, reason, rule, what, overrides);
        return new TaxChoice(c.rate(), c.overridden());
    }

    private static String orElse(String value, String fallback) {
        String v = blankToNull(value);
        return v != null ? v : blankToNull(fallback);
    }

    // Entities use Lombok @Data (hashCode walks the order<->line graph), so never use them as map keys
    private record LineQty(SaleOrderLine line, BigDecimal qty) {}

    private List<LineQty> resolveFulfilmentLines(SaleOrder so, FulfilmentRequest req,
                                                 Map<Long, AllocationService.LineAllocation> allocations) {
        List<LineQty> toShip = new ArrayList<>();

        if (req == null || req.getLines() == null || req.getLines().isEmpty()) {
            boolean anythingLeft = false;
            for (SaleOrderLine line : so.getLines()) {
                if (line.getRemainingQuantity().signum() > 0) anythingLeft = true;
                BigDecimal allocated = allocated(line, allocations);
                if (allocated.signum() > 0) toShip.add(new LineQty(line, allocated));
            }
            if (!anythingLeft) {
                throw new IllegalStateException("Nothing left to fulfil on " + so.getOrderNumber());
            }
            if (toShip.isEmpty()) {
                throw new IllegalStateException("Nothing is allocated to " + so.getOrderNumber()
                        + " yet: the stock on hand is promised to orders confirmed earlier");
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
            BigDecimal allocated = allocated(line, allocations);
            if (l.getQuantity().compareTo(allocated) > 0) {
                throw new IllegalStateException(String.format(
                        "Cannot ship %s of %s: only %s allocated to this order (the rest of the stock is promised to orders confirmed earlier)",
                        l.getQuantity().stripTrailingZeros().toPlainString(), line.getProduct().getSku(),
                        allocated.stripTrailingZeros().toPlainString()));
            }
            toShip.add(new LineQty(line, l.getQuantity()));
        }
        return toShip;
    }

    private static BigDecimal allocated(SaleOrderLine line, Map<Long, AllocationService.LineAllocation> allocations) {
        AllocationService.LineAllocation a = allocations.get(line.getId());
        return a == null ? BigDecimal.ZERO : a.allocated();
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
        // Changes made in this transaction must be visible to the allocation query
        soRepository.flush();
        SaleOrderResponse r = toResponse(so, allocationService.forOrders(List.of(so.getId())));
        r.setFulfilments(fulfilmentRepository.findBySaleOrderIdOrderByCreatedAtAsc(so.getId()).stream().map(f -> {
            SaleOrderResponse.FulfilmentEntry e = new SaleOrderResponse.FulfilmentEntry();
            e.setId(f.getId());
            e.setNotes(f.getNotes());
            e.setTrackingCompany(f.getTrackingCompany());
            e.setTrackingNumber(f.getTrackingNumber());
            e.setSyncStatus(f.getExternalSyncStatus());
            e.setSyncMessage(f.getExternalSyncMessage());
            e.setCreatedBy(f.getCreatedBy());
            e.setCreatedAt(f.getCreatedAt());
            e.setLines(f.getLines().stream()
                    .map(fl -> entryLine(fl.getSaleOrderLine(), fl.getQuantity(), null)).toList());
            return e;
        }).toList());
        setInvoices(r, invoiceService.forOrder(so.getId()).stream().map(this::invoiceEntry).toList());
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

    private SaleOrderResponse.InvoiceEntry invoiceEntry(InvoiceResponse inv) {
        SaleOrderResponse.InvoiceEntry e = new SaleOrderResponse.InvoiceEntry();
        e.setId(inv.getId());
        e.setInvoiceNumber(inv.getInvoiceNumber());
        e.setStatus(inv.getStatus());
        e.setIssueDate(inv.getIssueDate());
        e.setDueDate(inv.getDueDate());
        e.setTotal(inv.getTotalAmount());
        e.setAmountPaid(inv.getAmountPaid());
        e.setFulfilmentId(inv.getFulfilmentId());
        e.setKind(inv.getKind());
        return e;
    }

    private static void setInvoices(SaleOrderResponse r, List<SaleOrderResponse.InvoiceEntry> invoices) {
        r.setInvoices(invoices);
        r.setInvoicedTotal(total(invoices, Invoice.Kind.INVOICE));
        r.setCreditedTotal(total(invoices, Invoice.Kind.CREDIT_NOTE));
    }

    private static BigDecimal total(List<SaleOrderResponse.InvoiceEntry> invoices, Invoice.Kind kind) {
        return invoices.stream().filter(e -> e.getKind() == kind && e.getStatus() != Invoice.InvoiceStatus.CANCELLED)
                .map(SaleOrderResponse.InvoiceEntry::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
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
        return toResponse(so, Map.of());
    }

    private SaleOrderResponse toResponse(SaleOrder so, Map<Long, AllocationService.LineAllocation> allocations) {
        SaleOrderResponse r = new SaleOrderResponse();
        r.setId(so.getId());
        r.setOrderNumber(so.getOrderNumber());
        r.setCustomerId(so.getCustomer() != null ? so.getCustomer().getId() : null);
        r.setCustomerName(so.getCustomerName());
        r.setWarehouseId(so.getWarehouse() != null ? so.getWarehouse().getId() : null);
        r.setWarehouseName(so.getWarehouse() != null ? so.getWarehouse().getName() : null);
        r.setStatus(so.getStatus());
        r.setFulfilmentStatus(so.getFulfilmentStatus());
        r.setNotes(so.getNotes());
        r.setCustomerReference(so.getCustomerReference());
        r.setSalesRep(so.getSalesRep());
        r.setPaymentTerm(so.getPaymentTerm());
        r.setShipBy(so.getShipBy());
        r.setCarrier(so.getCarrier());
        r.setShippingNotes(so.getShippingNotes());
        r.setOrderedAt(so.getOrderedAt());
        r.setQuoteValidUntil(so.getQuoteValidUntil());
        r.setQuoteExpired(isExpired(so));
        r.setSource(so.getSource());
        r.setExternalReference(so.getExternalReference());
        r.setNeedsReview(so.isNeedsReview());
        r.setReviewReason(so.getReviewReason());
        r.setCreatedAt(so.getCreatedAt());
        r.setConfirmedAt(so.getConfirmedAt());
        r.setFulfilledAt(so.getFulfilledAt());
        r.setCancelledAt(so.getCancelledAt());

        BigDecimal[] totals = {BigDecimal.ZERO, BigDecimal.ZERO};
        // Gross (VAT included) not on an invoice yet
        BigDecimal[] toInvoice = {BigDecimal.ZERO};
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
            lr.setDiscountPercent(l.getDiscountPercent());
            lr.setTaxName(l.getTaxName());
            lr.setTaxRate(l.getTaxRate());
            lr.setTaxOverridden(l.isTaxOverridden());
            // Cancelled quantities are not sold
            BigDecimal qty = l.getQuantity().subtract(l.getCancelledQuantity());
            BigDecimal net = SaleAmounts.net(qty, l.getUnitPrice(), l.getDiscountPercent());
            BigDecimal tax = SaleAmounts.tax(net, l.getTaxRate());
            lr.setNetAmount(net);
            lr.setTaxAmount(tax);
            lr.setTotalAmount(net.add(tax));
            lr.setMarginPercent(SaleAmounts.marginPercent(net, qty, l.getProduct().getCostPrice()));
            lr.setInvoicedQuantity(l.getInvoicedQuantity());
            lr.setToInvoiceQuantity(l.getToInvoiceQuantity());
            BigDecimal openNet = SaleAmounts.net(l.getToInvoiceQuantity(), l.getUnitPrice(), l.getDiscountPercent());
            toInvoice[0] = toInvoice[0].add(openNet).add(SaleAmounts.tax(openNet, l.getTaxRate()));
            AllocationService.LineAllocation a = l.getId() == null ? null : allocations.get(l.getId());
            if (a != null) {
                lr.setAllocatedQuantity(a.allocated());
                lr.setBackorderedQuantity(a.backordered());
                if (a.backordered().signum() > 0) r.setBackordered(true);
            }
            totals[0] = totals[0].add(net);
            totals[1] = totals[1].add(tax);
            return lr;
        }).collect(Collectors.toList()));
        r.setCharges(so.getCharges().stream().map(c -> {
            SaleOrderResponse.Charge cr = new SaleOrderResponse.Charge();
            cr.setId(c.getId());
            cr.setDescription(c.getDescription());
            cr.setQuantity(c.getQuantity());
            cr.setUnitPrice(c.getUnitPrice());
            cr.setDiscountPercent(c.getDiscountPercent());
            cr.setTaxName(c.getTaxName());
            cr.setTaxRate(c.getTaxRate());
            cr.setTaxOverridden(c.isTaxOverridden());
            BigDecimal net = SaleAmounts.net(c.getQuantity(), c.getUnitPrice(), c.getDiscountPercent());
            BigDecimal tax = SaleAmounts.tax(net, c.getTaxRate());
            cr.setNetAmount(net);
            cr.setTaxAmount(tax);
            cr.setTotalAmount(net.add(tax));
            cr.setInvoiced(c.isInvoiced());
            if (!c.isInvoiced()) toInvoice[0] = toInvoice[0].add(net).add(tax);
            totals[0] = totals[0].add(net);
            totals[1] = totals[1].add(tax);
            return cr;
        }).collect(Collectors.toList()));
        r.setSubtotal(totals[0]);
        r.setTaxTotal(totals[1]);
        r.setTotal(totals[0].add(totals[1]));
        r.setToInvoiceTotal(toInvoice[0]);
        return r;
    }
}
