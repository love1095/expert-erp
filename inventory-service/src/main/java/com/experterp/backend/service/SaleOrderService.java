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
    private final CostingService costingService;
    private final PricingService pricingService;
    private final CreditService creditService;
    private final KitService kitService;
    private final ProductUnitService unitService;

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
        // Warning at order entry (G2)
        credit(saved, creditService.check(customer, saved.getTotal(), saved.getId()));
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
        SaleOrderResponse r = toResponse(so);
        // Warning at order entry (G2): the customer's standing with this order on top
        if (customer != null) credit(r, creditService.check(customer, r.getTotal(), null));
        return r;
    }

    private static void credit(SaleOrderResponse r, CreditService.CreditStatus st) {
        r.setCreditHold(st.held());
        r.setCreditWarning(st.warning());
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
        so.setPriceTier(built.getPriceTier());
        so.setPaymentTerm(built.getPaymentTerm());
        so.setShipBy(built.getShipBy());
        so.setCarrier(built.getCarrier());
        so.setShippingNotes(built.getShippingNotes());
        so.setBillingAddress(built.getBillingAddress());
        so.setShippingAddress(built.getShippingAddress());
        so.setShipToCompany(built.getShipToCompany());
        so.setShipToContact(built.getShipToContact());
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
        return confirm(id, null);
    }

    /**
     * Confirms a draft. A customer on credit hold (G2) stops it, unless a manager gives a reason to confirm
     * anyway (kept on the order, audited). Shopify orders were paid at checkout and are not checked.
     */
    @Transactional
    public SaleOrderResponse confirm(Long id, String creditOverride) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be confirmed (order is " + so.getStatus() + ")");
        }
        if (so.getCustomer() != null && so.getSource() == SaleOrder.Source.MANUAL) {
            BigDecimal total = toResponse(so).getTotal();
            CreditService.CreditStatus st = creditService.check(so.getCustomer(), total, so.getId());
            if (st.held()) {
                if (creditOverride == null || creditOverride.isBlank()) {
                    throw new IllegalStateException(st.warning() + ". A manager can confirm it anyway, with a reason");
                }
                if (!isManager()) throw new org.springframework.security.access.AccessDeniedException("Only a manager or admin can confirm past a credit hold");
                so.setCreditOverride(creditOverride.trim());
                auditService.log("SALE_ORDER", id, "CREDIT_OVERRIDE", Map.of("reason", creditOverride.trim(), "hold", st.warning()));
            }
        }
        checkDiscontinued(so);
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
     * Phase P6: a discontinued product is sold only while its stock lasts — no backorders. A line needing more than
     * the warehouse has free (on hand less what other orders reserve) stops the confirmation.
     */
    private void checkDiscontinued(SaleOrder so) {
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        for (SaleOrderLine l : so.getLines()) {
            if (l.getProduct().getStatus() == Product.Status.DISCONTINUED && l.getProduct().getType() != Product.Type.KIT
                    && l.getRemainingQuantity().signum() > 0) {
                wanted.merge(l.getProduct().getId(), l.getRemainingQuantity().multiply(l.getUnitFactor()), BigDecimal::add);
            }
        }
        if (wanted.isEmpty()) return;
        Long wid = so.getWarehouse().getId();
        Map<String, BigDecimal> reserved = allocationService.reservedByProductAndWarehouse(wanted.keySet());
        wanted.forEach((productId, qty) -> {
            BigDecimal res = reserved.getOrDefault(AllocationService.key(productId, wid), BigDecimal.ZERO);
            // A Shopify draft already reserves its own lines
            if (so.getSource() == SaleOrder.Source.SHOPIFY) res = res.subtract(qty).max(BigDecimal.ZERO);
            BigDecimal free = movementService.getStockLevel(productId, wid).getStock().subtract(res).max(BigDecimal.ZERO);
            if (qty.compareTo(free) > 0) {
                Product p = productService.getProduct(productId);
                throw new IllegalStateException(p.getSku() + " is discontinued and sold only while stock lasts: "
                        + free.stripTrailingZeros().toPlainString() + " left in " + so.getWarehouse().getName());
            }
        });
    }

    /**
     * Cin7's Undo: a confirmed order goes back to draft and its stock is no longer reserved. Not once anything
     * has been shipped or invoiced (return or credit it instead).
     */
    @Transactional
    public SaleOrderResponse undo(Long id) {
        SaleOrder so = lockSO(id);
        if (so.getStatus() != Status.CONFIRMED) {
            throw new IllegalStateException("Only confirmed orders can be undone (" + so.getOrderNumber() + " is " + so.getStatus() + ")");
        }
        if (so.hasFulfilledAnything()) {
            throw new IllegalStateException(so.getOrderNumber() + " has shipments: it cannot go back to draft");
        }
        if (so.getLines().stream().anyMatch(l -> l.getInvoicedQuantity().signum() > 0)
                || so.getCharges().stream().anyMatch(SaleOrderCharge::isInvoiced)) {
            throw new IllegalStateException(so.getOrderNumber() + " has invoices: cancel them first");
        }
        so.setStatus(Status.DRAFT);
        so.setConfirmedAt(null);
        auditService.log("SALE_ORDER", id, "UNDO", Map.of("status", Status.DRAFT));
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

        // A kit line ships its components (P2): those are the products whose stock moves
        Map<Long, Map<Long, BigDecimal>> recipes = new HashMap<>();
        so.getLines().forEach(l -> recipes.computeIfAbsent(l.getProduct().getId(), kitService::recipe));
        List<Long> stocked = new ArrayList<>();
        recipes.forEach((product, recipe) -> { if (recipe.isEmpty()) stocked.add(product); else stocked.addAll(recipe.keySet()); });
        movementService.lockProducts(stocked.stream().distinct().sorted().toList());
        Map<Long, AllocationService.LineAllocation> allocations = allocationService.forOrders(List.of(so.getId()));
        List<LineQty> toShip = resolveFulfilmentLines(so, req, allocations);

        // Check stock for all lines up front (summed per product) so the error names the real shortfall
        Map<Long, BigDecimal> perProduct = new HashMap<>();
        toShip.forEach(s -> {
            Map<Long, BigDecimal> recipe = recipes.get(s.line().getProduct().getId());
            BigDecimal base = s.qty().multiply(s.line().getUnitFactor());      // P3: in the product's own unit
            if (recipe.isEmpty()) perProduct.merge(s.line().getProduct().getId(), base, BigDecimal::add);
            else recipe.forEach((component, per) -> perProduct.merge(component, base.multiply(per), BigDecimal::add));
        });
        perProduct.forEach((productId, qty) ->
                movementService.assertSufficientStock(productId, so.getWarehouse().getId(), qty));

        Fulfilment f = new Fulfilment();
        f.setSaleOrder(so);
        f.setIdempotencyKey(key);
        f.setNotes(req != null ? req.getNotes() : null);
        f.setTrackingCompany(req != null ? blankToNull(req.getTrackingCompany()) : null);
        f.setTrackingNumber(req != null ? blankToNull(req.getTrackingNumber()) : null);
        f.setTrackingUrl(req != null ? blankToNull(req.getTrackingUrl()) : null);
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
            Map<Long, BigDecimal> recipe = recipes.get(s.line().getProduct().getId());
            BigDecimal base = s.qty().multiply(s.line().getUnitFactor());
            if (recipe.isEmpty()) {
                movementService.createMovement(
                        s.line().getProduct(), so.getWarehouse(), base,
                        MovementType.SALE_OUT, ReferenceType.SALE_ORDER,
                        "SO-" + so.getId(), "Fulfilment #" + f.getId() + " on SO #" + so.getId(), s.line().getId(), null);
            } else {
                // Each component at its own FIFO cost, against the kit's line (its cost of goods is their sum)
                recipe.forEach((component, per) -> movementService.createMovement(
                        productService.getProduct(component), so.getWarehouse(), base.multiply(per),
                        MovementType.SALE_OUT, ReferenceType.SALE_ORDER, "SO-" + so.getId(),
                        "Fulfilment #" + f.getId() + " on SO #" + so.getId() + " (kit " + s.line().getProduct().getSku() + ")", s.line().getId(), null));
            }
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
                Map<Long, BigDecimal> recipe = kitService.recipe(line.getProduct().getId());
                BigDecimal base = rl.getQuantity().multiply(line.getUnitFactor());     // P3: in the product's own unit
                if (recipe.isEmpty()) {
                    movementService.createMovement(
                            line.getProduct(), warehouse, base,
                            MovementType.SALE_RETURN_IN, ReferenceType.SALE_RETURN,
                            "RET-" + r.getId(), "Return #" + r.getId() + " on SO #" + so.getId(), line.getId(), null);
                } else {
                    // A returned kit puts its components back, each at the cost it left at
                    recipe.forEach((component, per) -> movementService.createMovement(
                            productService.getProduct(component), warehouse, base.multiply(per),
                            MovementType.SALE_RETURN_IN, ReferenceType.SALE_RETURN, "RET-" + r.getId(),
                            "Return #" + r.getId() + " on SO #" + so.getId() + " (kit " + line.getProduct().getSku() + ")", line.getId(), null));
                }
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
        // Addresses as typed on the order, else the customer's defaults (copied: later changes to the customer keep them)
        Address billing = Address.clean(req.getBillingAddress());
        Address shipping = Address.clean(req.getShippingAddress());
        so.setBillingAddress(billing != null ? billing : customer == null ? null : customer.billingAddress().map(Address::new).orElse(null));
        so.setShippingAddress(shipping != null ? shipping : customer == null ? null : customer.shippingAddress().map(Address::new).orElse(null));
        so.setShipToCompany(blankToNull(req.getShipToCompany()));
        so.setShipToContact(blankToNull(req.getShipToContact()));
        BigDecimal customerDiscount = customer != null && customer.getDiscount() != null ? customer.getDiscount() : BigDecimal.ZERO;
        // The customer's prices on the day (contract, tier, Retail)
        Map<Long, Product> products = new LinkedHashMap<>();
        for (SaleOrderRequest.Line l : req.getLines()) products.computeIfAbsent(l.getProductId(), productService::getProduct);
        // The order's tier: the one chosen on the order, else the customer's
        so.setPriceTier(pricingService.tierFor(customer, req.getPriceTier()).getName());
        Map<Long, PricingService.Price> prices = pricingService.prices(customer, products.values(), LocalDate.now(), so.getPriceTier());

        for (SaleOrderRequest.Line l : req.getLines()) {
            Product product = products.get(l.getProductId());
            SaleOrderLine line = new SaleOrderLine();
            line.setSaleOrder(so);
            line.setProduct(product);
            line.setQuantity(l.getQuantity());
            // Phase P3: sold in one of the product's units (a box of 100…): price per that unit
            ProductUnitService.Resolved unit = unitService.resolve(product, l.getUnitName());
            line.setUnitName(unit.name());
            line.setUnitFactor(unit.factor());
            PricingService.Price price = prices.get(product.getId());
            BigDecimal listed = price == null || price.unitPrice() == null ? null
                    : unit.base() ? price.unitPrice() : ProductUnitService.price(unit, price.unitPrice(), true);
            if (l.getUnitPrice() == null) {
                // A product with no selling price stays without one, as before (priced later on the order)
                if (listed != null) {
                    line.setUnitPrice(listed);
                    line.setPriceSource(unit.base() ? price.source() : price.source() + " (" + unit.name() + ")");
                }
            } else {
                line.setUnitPrice(l.getUnitPrice());
                // The customer's price kept on an edited order keeps its source
                line.setPriceSource(listed != null && listed.compareTo(l.getUnitPrice()) == 0
                        ? (unit.base() ? price.source() : price.source() + " (" + unit.name() + ")") : "Typed");
            }
            // A contract price is the agreed net price: no customer discount on top
            boolean contract = price != null && price.contract() && !"Typed".equals(line.getPriceSource());
            line.setDiscountPercent(l.getDiscountPercent() != null ? l.getDiscountPercent() : contract ? BigDecimal.ZERO : customerDiscount);
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
        r.setCreditOverride(so.getCreditOverride());
        // Credit hold (G2), while the order can still be stopped; Shopify orders are paid at checkout
        if (so.getCustomer() != null && so.getSource() == SaleOrder.Source.MANUAL
                && (so.getStatus() == Status.QUOTE || so.getStatus() == Status.DRAFT || so.getStatus() == Status.CONFIRMED)) {
            BigDecimal mine = so.getStatus() == Status.CONFIRMED ? r.getToInvoiceTotal() : r.getTotal();
            credit(r, creditService.check(so.getCustomer(), mine, so.getId()));
        }
        r.setFulfilments(fulfilmentRepository.findBySaleOrderIdOrderByCreatedAtAsc(so.getId()).stream().map(f -> {
            SaleOrderResponse.FulfilmentEntry e = new SaleOrderResponse.FulfilmentEntry();
            e.setId(f.getId());
            e.setNotes(f.getNotes());
            e.setTrackingCompany(f.getTrackingCompany());
            e.setTrackingNumber(f.getTrackingNumber());
            e.setTrackingUrl(f.getTrackingUrl());
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
        setCostOfGoods(r, so);
        return r;
    }

    /**
     * FIFO cost of goods per line (what its shipments took from the cost layers, less restocked returns) and the
     * margin over it; the part not shipped yet is still costed at the product's cost price.
     */
    private void setCostOfGoods(SaleOrderResponse r, SaleOrder so) {
        List<Long> lineIds = so.getLines().stream().map(SaleOrderLine::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, BigDecimal> cogs = costingService.costOfGoodsBySaleOrderLine(lineIds);
        if (cogs.isEmpty()) return;
        Map<Long, SaleOrderLine> lines = linesById(so);
        BigDecimal total = BigDecimal.ZERO;
        for (SaleOrderResponse.Line lr : r.getLines()) {
            BigDecimal shipped = cogs.get(lr.getId());
            if (shipped == null) continue;
            lr.setCostOfGoods(shipped);
            total = total.add(shipped);
            SaleOrderLine l = lines.get(lr.getId());
            BigDecimal unshipped = l.getQuantity().subtract(l.getCancelledQuantity()).subtract(l.getFulfilledQuantity()).max(BigDecimal.ZERO);
            BigDecimal costPrice = l.getProduct().getCostPrice();
            if (unshipped.signum() > 0 && costPrice == null) { lr.setMarginPercent(null); continue; }
            BigDecimal cost = shipped.add(unshipped.signum() > 0 ? unshipped.multiply(costPrice) : BigDecimal.ZERO);
            lr.setMarginPercent(lr.getNetAmount().signum() == 0 ? null : lr.getNetAmount().subtract(cost)
                    .multiply(BigDecimal.valueOf(100)).divide(lr.getNetAmount(), 2, java.math.RoundingMode.HALF_UP));
        }
        r.setCostOfGoods(total);
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
        r.setPriceTier(so.getPriceTier());
        r.setPaymentTerm(so.getPaymentTerm());
        r.setShipBy(so.getShipBy());
        r.setCarrier(so.getCarrier());
        r.setShippingNotes(so.getShippingNotes());
        r.setBillingAddress(Address.clean(so.getBillingAddress()));
        r.setShippingAddress(Address.clean(so.getShippingAddress()));
        r.setShipToCompany(so.getShipToCompany());
        r.setShipToContact(so.getShipToContact());
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
            lr.setUnitName(l.getUnitName());
            lr.setUnitFactor(l.getUnitFactor());
            lr.setUnitPrice(l.getUnitPrice());
            lr.setFulfilledQuantity(l.getFulfilledQuantity());
            lr.setCancelledQuantity(l.getCancelledQuantity());
            lr.setReturnedQuantity(l.getReturnedQuantity());
            lr.setRemainingQuantity(l.getRemainingQuantity());
            lr.setDiscountPercent(l.getDiscountPercent());
            lr.setTaxName(l.getTaxName());
            lr.setTaxRate(l.getTaxRate());
            lr.setTaxOverridden(l.isTaxOverridden());
            lr.setPriceSource(l.getPriceSource());
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

    // Cin7: "Sale - Ignore credit limit" (phase I1); jobs run without a user and may
    private static boolean isManager() {
        return com.experterp.backend.security.Permission.current(com.experterp.backend.security.Permission.SALE_IGNORE_CREDIT_LIMIT);
    }
}
