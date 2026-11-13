package com.experterp.backend.service;

import com.experterp.backend.dto.BillRequest;
import com.experterp.backend.dto.InvoiceRequest;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final com.experterp.backend.repository.CustomerRepository customerRepository;
    private final SaleOrderRepository saleOrderRepository;
    private final OrganisationSettingsService settingsService;
    private final PaymentRepository paymentRepository;
    private final com.experterp.backend.repository.PaymentAccountRepository paymentAccountRepository;
    private final com.experterp.backend.repository.CustomerCreditRepository customerCreditRepository;
    private final com.experterp.backend.repository.SupplierCreditRepository supplierCreditRepository;
    private final com.experterp.backend.repository.VoidedPaymentRepository voidedPaymentRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final LedgerPeriodService periodService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final TaxService taxService;
    private final CostingService costingService;
    private final PaymentTermService paymentTermService;

    @Transactional(readOnly = true)
    public Page<InvoiceResponse> findAll(int page, int size, Invoice.InvoiceType type) {
        return findAll(page, size, type, null, null);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceResponse> findAll(int page, int size, Invoice.InvoiceType type, Invoice.InvoiceStatus status, String q) {
        return findAll(page, size, type, status, false, q);
    }

    /** {@code overdue}: only issued invoices past their due date (status is then ignored). */
    @Transactional(readOnly = true)
    public Page<InvoiceResponse> findAll(int page, int size, Invoice.InvoiceType type, Invoice.InvoiceStatus status, boolean overdue, String q) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Invoice> found = overdue
                ? invoiceRepository.searchOverdue(type, LocalDate.now(), Search.pattern(q), pageable)
                : invoiceRepository.search(type, status, Search.pattern(q), pageable);
        return found.map(this::toResponse);
    }

    // Bills of several purchase orders, by order, in one query
    @Transactional(readOnly = true)
    public Map<Long, List<InvoiceResponse>> forPurchaseOrders(Collection<Long> purchaseOrderIds) {
        if (purchaseOrderIds.isEmpty()) return Map.of();
        return invoiceRepository.findByPurchaseOrderIdInOrderByCreatedAtAscIdAsc(purchaseOrderIds).stream()
                .collect(Collectors.groupingBy(Invoice::getPurchaseOrderId, Collectors.mapping(this::toResponse, Collectors.toList())));
    }

    @Transactional(readOnly = true)
    public InvoiceResponse findById(Long id) {
        return toResponse(getInvoice(id));
    }

    @Transactional
    public InvoiceResponse create(InvoiceRequest req) {
        Invoice invoice = new Invoice();
        invoice.setType(req.getType());
        invoice.setPartyName(req.getPartyName());
        invoice.setSaleOrderId(req.getSaleOrderId());
        invoice.setPurchaseOrderId(req.getPurchaseOrderId());
        if (req.getType() == Invoice.InvoiceType.SALES && req.getCustomerId() != null) {
            Customer c = customerRepository.findById(req.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + req.getCustomerId()));
            invoice.setCustomerId(c.getId());
        }
        invoice.setIssueDate(req.getIssueDate());
        invoice.setDueDate(req.getDueDate());
        invoice.setCurrency(req.getCurrency() != null ? req.getCurrency() : "GBP");
        invoice.setNotes(req.getNotes());
        // Q2: a hand-typed supplier invoice takes the bill series, not the sales invoice one
        invoice.setInvoiceNumber(req.getType() == Invoice.InvoiceType.PURCHASE ? settingsService.nextBillNumber() : settingsService.nextInvoiceNumber());

        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceRequest.ItemRequest ir : req.getItems()) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            if (ir.getProductId() != null) {
                Product p = productRepository.findById(ir.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + ir.getProductId()));
                item.setProduct(p);
            }
            item.setDescription(ir.getDescription());
            item.setQuantity(ir.getQuantity());
            item.setUnitPrice(ir.getUnitPrice());
            BigDecimal lineTotal = ir.getQuantity().multiply(ir.getUnitPrice());
            item.setTotalPrice(lineTotal);
            total = total.add(lineTotal);
            invoice.getItems().add(item);
        }
        // Entered by hand: no VAT recorded
        invoice.setSubtotal(total);
        invoice.setTotalAmount(total);

        Invoice saved = invoiceRepository.save(invoice);
        auditService.log("INVOICE", saved.getId(), "CREATE",
                Map.of("invoiceNumber", saved.getInvoiceNumber(), "type", req.getType(), "total", total));
        return toResponse(saved);
    }

    @Transactional
    public InvoiceResponse issue(Long id) {
        Invoice inv = getInvoice(id);
        if (inv.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT invoices can be issued");
        }
        inv.setStatus(Invoice.InvoiceStatus.ISSUED);
        if (inv.getIssueDate() == null) inv.setIssueDate(LocalDate.now());
        Invoice saved = invoiceRepository.save(inv);
        // A supplier bill prices the stock received (FIFO layers) from now on
        if (isBill(saved)) costingService.billIssued(saved);
        paidInShopify(saved);
        auditService.log("INVOICE", id, "ISSUE", Map.of("invoiceNumber", inv.getInvoiceNumber()));
        return toResponse(saved);
    }

    /**
     * Invoice made from a sale order: the given quantities (or everything not invoiced yet) of its lines, plus its
     * charges not invoiced yet. Prices, discounts and VAT are those of the order; due date from the order's terms.
     */
    @Transactional
    public InvoiceResponse invoiceOrder(Long orderId, OrderInvoiceRequest req) {
        SaleOrder so = saleOrderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale order not found: " + orderId));
        Map<Long, BigDecimal> quantities = null;
        if (req.getLines() != null && !req.getLines().isEmpty()) {
            quantities = new HashMap<>();
            for (OrderInvoiceRequest.Line l : req.getLines()) {
                if (quantities.put(l.getSaleOrderLineId(), l.getQuantity()) != null) {
                    throw new IllegalArgumentException("Line " + l.getSaleOrderLineId() + " appears more than once");
                }
            }
        }
        Invoice inv = invoiceFor(so, quantities, req.isIncludeCharges(), req.isIssue(), null, req.getNotes());
        if (inv == null) throw new IllegalStateException("Nothing left to invoice on " + so.getOrderNumber());
        return toResponse(inv);
    }

    /**
     * Shared by invoices made by hand and on shipment. {@code quantities}: per order line id, null = everything not
     * invoiced yet; asking for more than that is refused. Returns null when there is nothing to invoice.
     * The order must be locked by the caller's transaction.
     */
    @Transactional
    public Invoice invoiceFor(SaleOrder so, Map<Long, BigDecimal> quantities, boolean includeCharges, boolean issue,
                              Long fulfilmentId, String notes) {
        if (so.getStatus() != SaleOrder.Status.CONFIRMED && so.getStatus() != SaleOrder.Status.COMPLETED) {
            throw new IllegalStateException("Only confirmed or completed orders can be invoiced (" + so.getOrderNumber() + " is " + so.getStatus() + ")");
        }
        Invoice inv = new Invoice();
        inv.setType(Invoice.InvoiceType.SALES);
        inv.setPartyName(so.getCustomerName());
        inv.setCustomerId(so.getCustomer() != null ? so.getCustomer().getId() : null);
        inv.setSaleOrderId(so.getId());
        inv.setFulfilmentId(fulfilmentId);
        inv.setCurrency("GBP");
        inv.setNotes(notes);

        Set<Long> known = new HashSet<>();
        BigDecimal net = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (SaleOrderLine line : so.getLines()) {
            known.add(line.getId());
            BigDecimal qty = quantities == null ? line.getToInvoiceQuantity() : quantities.getOrDefault(line.getId(), BigDecimal.ZERO);
            if (qty.signum() <= 0) continue;
            if (qty.compareTo(line.getToInvoiceQuantity()) > 0) {
                throw new IllegalStateException(String.format("Cannot invoice %s of %s: only %s not invoiced yet",
                        qty.stripTrailingZeros().toPlainString(), line.getProduct().getSku(),
                        line.getToInvoiceQuantity().stripTrailingZeros().toPlainString()));
            }
            InvoiceItem item = item(inv, qty, line.getUnitPrice(), line.getDiscountPercent(), line.getTaxName(), line.getTaxRate());
            item.setProduct(line.getProduct());
            item.setDescription(line.getProduct().getSku() + ": " + line.getProduct().getName());
            item.setSaleOrderLineId(line.getId());
            item.setUnitName(line.getUnitName());
            line.setInvoicedQuantity(line.getInvoicedQuantity().add(qty));
            net = net.add(item.getTotalPrice());
            tax = tax.add(item.getTaxAmount());
        }
        if (quantities != null) {
            quantities.keySet().stream().filter(id -> !known.contains(id)).findFirst().ifPresent(id -> {
                throw new ResourceNotFoundException("Line " + id + " does not belong to " + so.getOrderNumber());
            });
        }
        if (includeCharges) {
            for (SaleOrderCharge c : so.getCharges()) {
                if (c.isInvoiced()) continue;
                InvoiceItem item = item(inv, c.getQuantity(), c.getUnitPrice(), c.getDiscountPercent(), c.getTaxName(), c.getTaxRate());
                item.setDescription(c.getDescription());
                item.setSaleOrderChargeId(c.getId());
                c.setInvoiced(true);
                net = net.add(item.getTotalPrice());
                tax = tax.add(item.getTaxAmount());
            }
        }
        if (inv.getItems().isEmpty()) return null;

        inv.setSubtotal(net);
        inv.setTaxTotal(tax);
        inv.setTotalAmount(net.add(tax));
        inv.setInvoiceNumber(settingsService.nextInvoiceNumber());
        LocalDate today = LocalDate.now();
        inv.setIssueDate(today);
        inv.setDueDate(paymentTermService.dueDate(so.getPaymentTerm(), today));
        if (issue) inv.setStatus(Invoice.InvoiceStatus.ISSUED);
        Invoice saved = invoiceRepository.save(inv);
        if (issue) paidInShopify(saved);
        Map<String, Object> audit = new HashMap<>(Map.of("invoiceNumber", saved.getInvoiceNumber(), "order", so.getOrderNumber(),
                "total", saved.getTotalAmount(), "status", saved.getStatus()));
        if (fulfilmentId != null) audit.put("fulfilmentId", fulfilmentId);
        auditService.log("INVOICE", saved.getId(), "CREATE", audit);
        return saved;
    }

    /**
     * A supplier's bill for a purchase order (as in Cin7, PO-19064's 006387333): the supplier's invoice number, a due date
     * from the order's terms, the lines received and not billed yet (or those given) at the PO price unless the bill says
     * otherwise, and the supplier's extra charges (an expense). Ordered vs received vs billed and price differences are
     * flagged on the bill's lines (three-way match).
     */
    @Transactional
    public InvoiceResponse billPurchaseOrder(Long purchaseOrderId, BillRequest req) {
        return billPurchaseOrder(purchaseOrderId, req, true);
    }

    /**
     * As {@link #billPurchaseOrder(Long, BillRequest)}; {@code followApproach} false only for history brought over from
     * Cin7, where the bill and the receipt already happened in their own order.
     */
    @Transactional
    public InvoiceResponse billPurchaseOrder(Long purchaseOrderId, BillRequest req, boolean followApproach) {
        PurchaseOrder po = purchaseOrderRepository.findByIdForUpdate(purchaseOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + purchaseOrderId));
        if (po.getStatus() != PurchaseOrder.Status.ORDERED && po.getStatus() != PurchaseOrder.Status.RECEIVED) {
            throw new IllegalStateException("Only ordered or received purchase orders can be billed (" + po.getOrderNumber() + " is " + po.getStatus() + ")");
        }
        String number = req.getSupplierInvoiceNumber().trim();
        Long supplierId = po.getSupplier() != null ? po.getSupplier().getId() : null;
        if (supplierId != null && invoiceRepository.existsByTypeAndSupplierIdAndInvoiceNumberIgnoreCaseAndStatusNot(
                Invoice.InvoiceType.PURCHASE, supplierId, number, Invoice.InvoiceStatus.CANCELLED)) {
            throw new IllegalStateException(po.getSupplierName() + "'s invoice " + number + " is already entered");
        }
        Invoice inv = new Invoice();
        inv.setType(Invoice.InvoiceType.PURCHASE);
        inv.setInvoiceNumber(number);
        inv.setPartyName(po.getSupplierName());
        inv.setSupplierId(supplierId);
        inv.setPurchaseOrderId(po.getId());
        inv.setCurrency("GBP");
        inv.setNotes(req.getNotes());

        // Which lines: the given ones, or everything received and not billed yet
        Map<Long, BillRequest.Line> given = new HashMap<>();
        for (BillRequest.Line l : req.getLines()) {
            if (given.put(l.getPurchaseOrderLineId(), l) != null) throw new IllegalArgumentException("Line " + l.getPurchaseOrderLineId() + " appears more than once");
        }
        Set<Long> known = new HashSet<>();
        BigDecimal net = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (PurchaseOrderLine line : po.getLines()) {
            known.add(line.getId());
            BillRequest.Line g = given.get(line.getId());
            BigDecimal qty = !given.isEmpty() ? (g != null ? g.getQuantity() : BigDecimal.ZERO)
                    : line.getReceivedQuantity().subtract(line.getBilledQuantity()).max(BigDecimal.ZERO);
            if (qty.signum() <= 0) continue;
            if (qty.compareTo(line.getToBillQuantity()) > 0) {
                throw new IllegalStateException(String.format("Cannot bill %s of %s: only %s left to bill",
                        qty.stripTrailingZeros().toPlainString(), line.getProduct().getSku(),
                        line.getToBillQuantity().stripTrailingZeros().toPlainString()));
            }
            // Stock first (Cin7's Approach): the bill covers only what came in and is not billed yet
            BigDecimal inNotBilled = line.getReceivedQuantity().subtract(line.getBilledQuantity()).max(BigDecimal.ZERO);
            if (followApproach && po.getApproach() == PurchaseOrder.Approach.STOCK_FIRST && qty.compareTo(inNotBilled) > 0) {
                throw new IllegalStateException(String.format(
                        "%s is Stock first: only %s of %s has come in and is not billed yet. Receive the goods first",
                        po.getOrderNumber(), inNotBilled.stripTrailingZeros().toPlainString(), line.getProduct().getSku()));
            }
            BigDecimal price = g != null && g.getUnitPrice() != null ? g.getUnitPrice() : line.getUnitCost();
            InvoiceItem item = item(inv, qty, price, line.getDiscountPercent(), line.getTaxName(), line.getTaxRate());
            item.setProduct(line.getProduct());
            item.setDescription(line.getProduct().getSku() + ": " + line.getProduct().getName());
            item.setPurchaseOrderLineId(line.getId());
            item.setUnitName(line.getUnitName());
            line.setBilledQuantity(line.getBilledQuantity().add(qty));
            net = net.add(item.getTotalPrice());
            tax = tax.add(item.getTaxAmount());
        }
        given.keySet().stream().filter(id -> !known.contains(id)).findFirst().ifPresent(id -> {
            throw new ResourceNotFoundException("Line " + id + " does not belong to " + po.getOrderNumber());
        });
        for (BillRequest.Charge c : req.getCharges()) {
            TaxRate rate = c.getTaxRule() == null || c.getTaxRule().isBlank()
                    ? taxService.resolvePurchase(null, po.getSupplier()) : taxService.rate(TaxRate.Usage.PURCHASE, c.getTaxRule());
            InvoiceItem item = item(inv, c.getQuantity() != null ? c.getQuantity() : BigDecimal.ONE, c.getUnitPrice(), BigDecimal.ZERO,
                    rate.getName(), rate.getRate());
            item.setDescription(c.getDescription().trim());
            net = net.add(item.getTotalPrice());
            tax = tax.add(item.getTaxAmount());
        }
        if (inv.getItems().isEmpty()) throw new IllegalStateException("Nothing to bill on " + po.getOrderNumber() + ": enter the quantities billed");

        inv.setSubtotal(net);
        inv.setTaxTotal(tax);
        inv.setTotalAmount(net.add(tax));
        LocalDate issued = req.getIssueDate() != null ? req.getIssueDate() : LocalDate.now();
        periodService.check(issued, "a bill");
        inv.setIssueDate(issued);
        inv.setDueDate(req.getDueDate() != null ? req.getDueDate() : paymentTermService.dueDate(po.getPaymentTerm(), issued));
        if (!req.isDraft()) inv.setStatus(Invoice.InvoiceStatus.ISSUED);
        Invoice saved = invoiceRepository.save(inv);
        // Bill prices other than the PO's correct the stock received and the COGS already taken from it (FIFO)
        if (saved.getStatus() == Invoice.InvoiceStatus.ISSUED) costingService.billIssued(saved);
        InvoiceResponse r = toResponse(saved);
        auditService.log("INVOICE", saved.getId(), "CREATE", Map.of("bill", number, "order", po.getOrderNumber(),
                "total", saved.getTotalAmount(), "status", saved.getStatus(), "matchIssues", r.getMatchIssueCount()));
        return r;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> forPurchaseOrder(Long purchaseOrderId) {
        return invoiceRepository.findByPurchaseOrderIdOrderByCreatedAtAscIdAsc(purchaseOrderId).stream().map(this::toResponse).toList();
    }

    // Three-way match for one bill line: the PO price, and billed vs ordered vs received on the PO line
    private List<String> matchIssues(InvoiceItem item, PurchaseOrderLine line) {
        List<String> issues = new ArrayList<>();
        BigDecimal poPrice = line.getUnitCost() == null ? BigDecimal.ZERO : line.getUnitCost();
        if (item.getUnitPrice().compareTo(poPrice) != 0) {
            issues.add("Price " + money(item.getUnitPrice()) + " differs from the PO (" + money(poPrice) + ")");
        }
        if (line.getBilledQuantity().compareTo(line.getReceivedQuantity()) > 0) {
            issues.add("Billed " + qty(line.getBilledQuantity()) + ", received " + qty(line.getReceivedQuantity()));
        }
        if (line.getBilledQuantity().compareTo(line.getQuantity()) > 0) {
            issues.add("Billed " + qty(line.getBilledQuantity()) + ", ordered " + qty(line.getQuantity()));
        }
        return issues;
    }

    private static String money(BigDecimal v) {
        return "£" + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String qty(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static InvoiceItem item(Invoice inv, BigDecimal qty, BigDecimal unitPrice, BigDecimal discount, String taxName, BigDecimal taxRate) {
        InvoiceItem item = new InvoiceItem();
        item.setInvoice(inv);
        item.setQuantity(qty);
        item.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
        item.setDiscountPercent(discount);
        item.setTaxName(taxName);
        item.setTaxRate(taxRate);
        // H3: a 100 % (Full VAT) line is all VAT
        BigDecimal[] split = SaleAmounts.split(qty, unitPrice, discount, taxRate);
        item.setTotalPrice(split[0]);
        item.setTaxAmount(split[1]);
        inv.getItems().add(item);
        return item;
    }

    @Transactional(readOnly = true)
    public List<InvoiceResponse> forOrder(Long saleOrderId) {
        return invoiceRepository.findBySaleOrderIdOrderByCreatedAtAscIdAsc(saleOrderId).stream().map(this::toResponse).toList();
    }

    // Invoices and credit notes of several orders, by order, in one query
    @Transactional(readOnly = true)
    public java.util.Map<Long, List<InvoiceResponse>> forOrders(java.util.Collection<Long> saleOrderIds) {
        if (saleOrderIds.isEmpty()) return java.util.Map.of();
        return invoiceRepository.findBySaleOrderIdInOrderByCreatedAtAscIdAsc(saleOrderIds).stream()
                .collect(java.util.stream.Collectors.groupingBy(Invoice::getSaleOrderId,
                        java.util.stream.Collectors.mapping(this::toResponse, java.util.stream.Collectors.toList())));
    }

    /** Cancelling an invoice made from an order puts its quantities and charges back to "to invoice". */
    @Transactional
    public InvoiceResponse cancel(Long id) {
        Invoice inv = getInvoice(id);
        if (inv.getKind() == Invoice.Kind.CREDIT_NOTE) {
            throw new IllegalStateException("A credit note cannot be cancelled; invoice the amount again instead");
        }
        if (inv.getStatus() == Invoice.InvoiceStatus.PAID) {
            throw new IllegalStateException("Cannot cancel a paid invoice");
        }
        if (inv.getStatus() == Invoice.InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("The invoice is already cancelled");
        }
        if (inv.getStatus() != Invoice.InvoiceStatus.DRAFT) periodService.check(inv.getIssueDate(), inv.getInvoiceNumber());
        if (inv.getItems().stream().anyMatch(i -> i.getCreditedQuantity().signum() > 0)) {
            throw new IllegalStateException(inv.getInvoiceNumber() + " has credit notes and cannot be cancelled");
        }
        // A bill gives its quantities back to "to bill" on the purchase order
        if (inv.getPurchaseOrderId() != null && inv.getItems().stream().anyMatch(i -> i.getPurchaseOrderLineId() != null)) {
            PurchaseOrder po = purchaseOrderRepository.findByIdForUpdate(inv.getPurchaseOrderId()).orElseThrow();
            for (InvoiceItem item : inv.getItems()) {
                po.getLines().stream().filter(l -> l.getId().equals(item.getPurchaseOrderLineId())).findFirst()
                        .ifPresent(l -> l.setBilledQuantity(l.getBilledQuantity().subtract(item.getQuantity()).max(BigDecimal.ZERO)));
            }
        }
        if (inv.getSaleOrderId() != null && inv.getItems().stream()
                .anyMatch(i -> i.getSaleOrderLineId() != null || i.getSaleOrderChargeId() != null)) {
            SaleOrder so = saleOrderRepository.findByIdForUpdate(inv.getSaleOrderId()).orElseThrow();
            for (InvoiceItem item : inv.getItems()) {
                so.getLines().stream().filter(l -> l.getId().equals(item.getSaleOrderLineId())).findFirst()
                        .ifPresent(l -> l.setInvoicedQuantity(l.getInvoicedQuantity().subtract(item.getQuantity()).max(BigDecimal.ZERO)));
                so.getCharges().stream().filter(c -> c.getId().equals(item.getSaleOrderChargeId())).findFirst()
                        .ifPresent(c -> c.setInvoiced(false));
            }
        }
        boolean wasIssued = inv.getStatus() == Invoice.InvoiceStatus.ISSUED;
        inv.setStatus(Invoice.InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        // An issued bill's cost corrections are taken back (opposite adjustments; history is never edited)
        if (wasIssued && isBill(saved)) costingService.billCancelled(saved);
        auditService.log("INVOICE", id, "CANCEL", Map.of("invoiceNumber", inv.getInvoiceNumber()));
        return toResponse(saved);
    }

    /**
     * Records money paid on an invoice, a bill, or (a refund) a credit note: on a day (not in the future), to a payment
     * account, with a reference (phase H1). A sales invoice paid more than is due takes what is due and the rest becomes
     * the customer's credit (Cin7's Customer credit); an overpaid bill leaves the rest with the supplier (H2); a refund
     * cannot be more than is due.
     */
    @Transactional
    public InvoiceResponse addPayment(Long id, PaymentRequest req) {
        Invoice inv = getInvoice(id);
        if (inv.getStatus() == Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Issue the invoice before recording a payment");
        }
        if (inv.getStatus() == Invoice.InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Cannot pay a cancelled invoice");
        }
        if (inv.getStatus() == Invoice.InvoiceStatus.PAID) {
            throw new IllegalStateException("Invoice is already fully paid");
        }
        LocalDate day = req.getPaidOn() != null ? req.getPaidOn() : LocalDate.now();
        if (day.isAfter(LocalDate.now())) throw new IllegalArgumentException("A payment cannot be dated in the future");
        periodService.check(day, "a payment");
        PaymentAccount account = paymentAccount(req.getAccountId());
        Payment.PaymentMethod method = req.getMethod() != null ? req.getMethod() : account != null ? account.getKind().method() : null;
        if (method == null) throw new IllegalArgumentException("Choose the payment account (or how it was paid)");
        if (method.isCredit()) throw new IllegalArgumentException("Use the credit buttons ('Use customer credit', 'Use supplier deposit', 'Allocate credit note') for credit");

        BigDecimal due = inv.getTotalAmount().subtract(settled(inv));
        BigDecimal amount = req.getAmount();
        BigDecimal extra = amount.subtract(due);
        boolean salesInvoice = inv.getType() == Invoice.InvoiceType.SALES && inv.getKind() == Invoice.Kind.INVOICE;
        boolean bill = inv.getType() == Invoice.InvoiceType.PURCHASE && inv.getKind() == Invoice.Kind.INVOICE;
        if (extra.signum() > 0 && !(salesInvoice && inv.getCustomerId() != null) && !(bill && inv.getSupplierId() != null)) {
            throw new IllegalArgumentException("£" + amount.setScale(2) + " is more than the £" + due.setScale(2) + " still due on " + inv.getInvoiceNumber());
        }
        String reference = req.getReference() == null || req.getReference().isBlank() ? null : req.getReference().trim();

        Payment payment = new Payment();
        payment.setInvoice(inv);
        payment.setAmount(extra.signum() > 0 ? due : amount);
        payment.setMethod(method);
        payment.setPaidAt(day.equals(LocalDate.now()) ? LocalDateTime.now() : day.atTime(12, 0));
        payment.setPaymentAccountId(account != null ? account.getId() : null);
        payment.setReference(reference);
        paymentRepository.save(payment);
        inv.getPayments().add(payment);

        Map<String, Object> audit = new HashMap<>(Map.of("amount", amount, "method", method, "paidOn", day.toString()));
        if (account != null) audit.put("account", account.label());
        if (reference != null) audit.put("reference", reference);
        if (extra.signum() > 0 && bill) {
            // H2: an overpaid bill leaves the rest with the supplier, used on the next bill (Cin7's supplier deposit)
            SupplierCredit credit = new SupplierCredit();
            credit.setSupplierId(inv.getSupplierId());
            credit.setAmount(extra);
            credit.setPaidOn(day);
            credit.setMethod(method);
            credit.setPaymentAccountId(payment.getPaymentAccountId());
            credit.setReference(reference);
            credit.setSourceInvoiceId(inv.getId());
            credit.setPurchaseOrderId(inv.getPurchaseOrderId());
            credit.setNote("Overpaid " + inv.getInvoiceNumber());
            credit.setCreatedBy(auditService.currentActorName());
            supplierCreditRepository.save(credit);
            audit.put("toSupplierCredit", extra);
        } else if (extra.signum() > 0) {
            CustomerCredit credit = new CustomerCredit();
            credit.setCustomerId(inv.getCustomerId());
            credit.setAmount(extra);
            credit.setReceivedOn(day);
            credit.setMethod(method);
            credit.setPaymentAccountId(payment.getPaymentAccountId());
            credit.setReference(reference);
            credit.setSourceInvoiceId(inv.getId());
            credit.setNote("Overpaid " + inv.getInvoiceNumber());
            credit.setCreatedBy(auditService.currentActorName());
            customerCreditRepository.save(credit);
            audit.put("toCustomerCredit", extra);
        }
        BigDecimal totalPaid = settled(inv);
        audit.put("totalPaid", totalPaid);
        if (totalPaid.compareTo(inv.getTotalAmount()) >= 0) {
            inv.setStatus(Invoice.InvoiceStatus.PAID);
            invoiceRepository.save(inv);
        }
        auditService.log("INVOICE", id, "PAYMENT", audit);
        return toResponse(inv);
    }

    /**
     * A Shopify order is only confirmed once Shopify says it is paid (checkout), so its invoice is paid when issued:
     * a Shopify payment on the organisation's Shopify payment account, if it has one (H1). Shopify refunds come back as
     * credit notes refunded through Shopify (7.4).
     */
    private void paidInShopify(Invoice inv) {
        if (inv.getType() != Invoice.InvoiceType.SALES || inv.getKind() != Invoice.Kind.INVOICE || inv.getSaleOrderId() == null) return;
        SaleOrder so = saleOrderRepository.findById(inv.getSaleOrderId()).orElse(null);
        if (so == null || so.getSource() != SaleOrder.Source.SHOPIFY) return;
        BigDecimal due = inv.getTotalAmount().subtract(settled(inv));
        if (due.signum() <= 0) return;
        Payment p = new Payment();
        p.setInvoice(inv);
        p.setAmount(due);
        p.setMethod(Payment.PaymentMethod.SHOPIFY);
        p.setReference(so.getExternalReference());
        paymentAccountRepository.findFirstByKindAndActiveTrueOrderByCodeAsc(PaymentAccount.Kind.SHOPIFY).ifPresent(a -> p.setPaymentAccountId(a.getId()));
        paymentRepository.save(p);
        inv.getPayments().add(p);
        inv.setStatus(Invoice.InvoiceStatus.PAID);
        auditService.log("INVOICE", inv.getId(), "PAYMENT", Map.of("amount", due, "method", "SHOPIFY", "reference",
                String.valueOf(so.getExternalReference()), "note", "paid at Shopify checkout"));
    }

    /** What has settled the invoice: its payments, and for a credit note also what was used to pay invoices. */
    BigDecimal settled(Invoice inv) {
        BigDecimal paid = inv.getPayments().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (inv.getKind() == Invoice.Kind.CREDIT_NOTE && inv.getId() != null) paid = paid.add(paymentRepository.sumAppliedFromCreditNote(inv.getId()));
        return paid;
    }

    /** The chosen payment account (must be active), else the organisation's default, else none. */
    PaymentAccount paymentAccount(Long id) {
        if (id == null) return paymentAccountRepository.findFirstByDefaultAccountTrueAndActiveTrue().orElse(null);
        PaymentAccount a = paymentAccountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment account not found: " + id));
        if (!a.isActive()) throw new IllegalArgumentException(a.label() + " is no longer used");
        return a;
    }

    private Invoice getInvoice(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
    }

    private static boolean isBill(Invoice inv) {
        return inv.getType() == Invoice.InvoiceType.PURCHASE && inv.getKind() == Invoice.Kind.INVOICE && inv.getPurchaseOrderId() != null;
    }

    private InvoiceResponse toResponse(Invoice inv) {
        InvoiceResponse r = new InvoiceResponse();
        r.setId(inv.getId());
        r.setInvoiceNumber(inv.getInvoiceNumber());
        r.setType(inv.getType());
        r.setPartyName(inv.getPartyName());
        r.setSaleOrderId(inv.getSaleOrderId());
        r.setPurchaseOrderId(inv.getPurchaseOrderId());
        r.setSupplierId(inv.getSupplierId());
        // Bills: the PO's lines for the three-way match
        PurchaseOrder po = inv.getPurchaseOrderId() == null ? null : purchaseOrderRepository.findById(inv.getPurchaseOrderId()).orElse(null);
        if (po != null) {
            r.setPurchaseOrderNumber(po.getOrderNumber());
            if (inv.getKind() == Invoice.Kind.INVOICE) r.setCostAdjustment(costingService.billCostAdjustment(inv));
        }
        r.setStatus(inv.getStatus());
        r.setIssueDate(inv.getIssueDate());
        r.setDueDate(inv.getDueDate());
        r.setTotalAmount(inv.getTotalAmount());
        r.setSubtotal(inv.getSubtotal());
        r.setTaxTotal(inv.getTaxTotal());
        r.setCustomerId(inv.getCustomerId());
        r.setFulfilmentId(inv.getFulfilmentId());
        r.setKind(inv.getKind());
        if (inv.getCreditedInvoiceId() != null) {
            r.setCreditedInvoiceId(inv.getCreditedInvoiceId());
            invoiceRepository.findById(inv.getCreditedInvoiceId()).ifPresent(c -> r.setCreditedInvoiceNumber(c.getInvoiceNumber()));
        }
        r.setCurrency(inv.getCurrency());
        r.setNotes(inv.getNotes());
        r.setCreatedAt(inv.getCreatedAt());

        r.setItems(inv.getItems().stream().map(item -> {
            InvoiceResponse.ItemResponse ir = new InvoiceResponse.ItemResponse();
            ir.setId(item.getId());
            if (item.getProduct() != null) {
                ir.setProductId(item.getProduct().getId());
                ir.setProductName(item.getProduct().getName());
                ir.setProductSku(item.getProduct().getSku());
            }
            ir.setDescription(item.getDescription());
            ir.setQuantity(item.getQuantity());
            ir.setUnitPrice(item.getUnitPrice());
            ir.setUnitName(item.getUnitName());
            ir.setTotalPrice(item.getTotalPrice());
            ir.setDiscountPercent(item.getDiscountPercent());
            ir.setTaxName(item.getTaxName());
            ir.setTaxRate(item.getTaxRate());
            ir.setTaxAmount(item.getTaxAmount());
            ir.setSaleOrderLineId(item.getSaleOrderLineId());
            ir.setSaleOrderChargeId(item.getSaleOrderChargeId());
            ir.setPurchaseOrderLineId(item.getPurchaseOrderLineId());
            if (po != null && item.getPurchaseOrderLineId() != null && inv.getStatus() != Invoice.InvoiceStatus.CANCELLED) {
                po.getLines().stream().filter(l -> l.getId().equals(item.getPurchaseOrderLineId())).findFirst()
                        .ifPresent(l -> ir.setMatchIssues(matchIssues(item, l)));
            }
            ir.setCreditedQuantity(item.getCreditedQuantity());
            if (item.getPurchaseOrderLineId() != null) ir.setCostMatchedQuantity(item.getCostMatchedQuantity());
            return ir;
        }).collect(Collectors.toList()));
        r.setMatchIssueCount((int) r.getItems().stream().filter(i -> i.getMatchIssues() != null && !i.getMatchIssues().isEmpty()).count());

        r.setPayments(inv.getPayments().stream().map(p -> {
            InvoiceResponse.PaymentResponse pr = new InvoiceResponse.PaymentResponse();
            pr.setId(p.getId());
            pr.setAmount(p.getAmount());
            pr.setMethod(p.getMethod());
            pr.setPaidAt(p.getPaidAt());
            pr.setReference(p.getReference());
            pr.setCreditNoteId(p.getCreditNoteId());
            pr.setCustomerCreditId(p.getCustomerCreditId());
            pr.setSupplierCreditId(p.getSupplierCreditId());
            if (p.getPaymentAccountId() != null) {
                pr.setAccountId(p.getPaymentAccountId());
                paymentAccountRepository.findById(p.getPaymentAccountId()).ifPresent(a -> pr.setAccount(a.label()));
            }
            return pr;
        }).collect(Collectors.toList()));
        if (inv.getId() != null) {
            r.setVoidedPayments(voidedPaymentRepository.findByInvoiceIdOrderByVoidedAtAsc(inv.getId()).stream().map(v -> {
                InvoiceResponse.VoidedPaymentResponse x = new InvoiceResponse.VoidedPaymentResponse();
                x.setPaymentId(v.getPaymentId());
                x.setAmount(v.getAmount());
                x.setMethod(v.getMethod());
                x.setPaidAt(v.getPaidAt());
                x.setReference(v.getReference());
                x.setReason(v.getReason());
                x.setVoidedBy(v.getVoidedBy());
                x.setVoidedAt(v.getVoidedAt());
                return x;
            }).toList());
        }

        BigDecimal paid = inv.getPayments().stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Credit notes: refunds plus what was used to pay invoices
        if (inv.getKind() == Invoice.Kind.CREDIT_NOTE && inv.getId() != null) {
            BigDecimal applied = paymentRepository.sumAppliedFromCreditNote(inv.getId());
            r.setCreditApplied(applied);
            paid = paid.add(applied);
        }
        r.setAmountPaid(paid);

        return r;
    }
}
