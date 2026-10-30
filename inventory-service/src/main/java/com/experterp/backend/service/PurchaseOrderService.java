package com.experterp.backend.service;

import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.PurchaseOrderRepository;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.repository.PurchaseReceiptRepository;
import com.experterp.backend.dto.PurchaseReceiptRequest;
import com.experterp.backend.dto.ReorderRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.repository.WarehouseRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PurchaseOrderService {

    private final PurchaseOrderRepository poRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductService productService;
    private final StockMovementService movementService;
    private final AuditService auditService;
    private final SupplierRepository supplierRepository;
    private final TaxService taxService;
    private final OrganisationSettingsService settingsService;
    private final PurchaseReceiptRepository receiptRepository;
    private final InvoiceService invoiceService;

    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> findAll(int page, int size, PurchaseOrder.Status status, String q) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return poRepository.search(status, Search.pattern(q), pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse findById(Long id) {
        return toDetailResponse(getPO(id));
    }

    private PurchaseOrderResponse toDetailResponse(PurchaseOrder po) {
        PurchaseOrderResponse r = toResponse(po);
        List<InvoiceResponse> bills = invoiceService.forPurchaseOrder(po.getId());
        r.setBills(bills.stream().map(b -> {
            PurchaseOrderResponse.Bill e = new PurchaseOrderResponse.Bill();
            e.setId(b.getId());
            e.setInvoiceNumber(b.getInvoiceNumber());
            e.setStatus(b.getStatus());
            e.setIssueDate(b.getIssueDate());
            e.setDueDate(b.getDueDate());
            e.setTotal(b.getTotalAmount());
            e.setAmountPaid(b.getAmountPaid());
            e.setMatchIssueCount(b.getMatchIssueCount());
            return e;
        }).toList());
        List<InvoiceResponse> live = bills.stream().filter(b -> b.getStatus() != Invoice.InvoiceStatus.CANCELLED).toList();
        if (!live.isEmpty()) {
            boolean allPaid = live.stream().allMatch(b -> b.getStatus() == Invoice.InvoiceStatus.PAID);
            boolean anyPaid = live.stream().anyMatch(b -> b.getAmountPaid() != null && b.getAmountPaid().signum() > 0);
            r.setPaymentStatus(allPaid ? "PAID" : anyPaid ? "PARTIALLY_PAID" : "UNPAID");
        }
        r.setReceipts(receiptRepository.findByPurchaseOrderIdOrderByCreatedAtAscIdAsc(po.getId()).stream().map(rc -> {
            PurchaseOrderResponse.Receipt e = new PurchaseOrderResponse.Receipt();
            e.setId(rc.getId());
            e.setReceivedOn(rc.getReceivedOn());
            e.setWarehouseName(rc.getWarehouse().getName());
            e.setNotes(rc.getNotes());
            e.setCreatedBy(rc.getCreatedBy());
            e.setCreatedAt(rc.getCreatedAt());
            e.setLines(rc.getLines().stream().map(rl -> {
                PurchaseOrderResponse.ReceiptLine x = new PurchaseOrderResponse.ReceiptLine();
                x.setPurchaseOrderLineId(rl.getPurchaseOrderLine().getId());
                x.setProductSku(rl.getPurchaseOrderLine().getProduct().getSku());
                x.setProductName(rl.getPurchaseOrderLine().getProduct().getName());
                x.setQuantity(rl.getQuantity());
                x.setOverQuantity(rl.getOverQuantity());
                return x;
            }).toList());
            return e;
        }).toList());
        return r;
    }

    @Transactional
    public PurchaseOrderResponse create(PurchaseOrderRequest req) {
        List<Map<String, Object>> taxOverrides = new ArrayList<>();
        PurchaseOrder po = build(new PurchaseOrder(), req, taxOverrides);
        po.setOrderNumber(settingsService.nextPurchaseOrderNumber());
        if (req.isOrdered()) {
            po.setStatus(PurchaseOrder.Status.ORDERED);
            po.setOrderedAt(LocalDateTime.now());
        }
        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        Map<String, Object> audit = new LinkedHashMap<>(Map.of("number", po.getOrderNumber(), "supplier", po.getSupplierName(),
                "lines", po.getLines().size(), "status", po.getStatus().name()));
        if (!taxOverrides.isEmpty()) audit.put("taxOverrides", taxOverrides);
        auditService.log("PURCHASE_ORDER", saved.getId(), "CREATE", audit);
        return saved;
    }

    /**
     * Cin7's "Reorder": the products chosen on the reorder screen become one draft PO per default supplier, each line
     * with the supplier's code, price and VAT as on any new PO. Products without a supplier are refused by name.
     */
    @Transactional
    public List<PurchaseOrderResponse> createFromReorder(ReorderRequest req) {
        Map<Long, List<ReorderRequest.Line>> bySupplier = new LinkedHashMap<>();
        List<String> noSupplier = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (ReorderRequest.Line l : req.getLines()) {
            Product product = productService.getProduct(l.getProductId());
            if (!seen.add(product.getId())) throw new IllegalArgumentException(product.getSku() + " appears more than once");
            if (product.getDefaultSupplier() == null) noSupplier.add(product.getSku());
            else bySupplier.computeIfAbsent(product.getDefaultSupplier().getId(), k -> new ArrayList<>()).add(l);
        }
        if (!noSupplier.isEmpty()) {
            throw new IllegalArgumentException("No supplier set for " + String.join(", ", noSupplier) + ": give them one on the product first");
        }
        List<PurchaseOrderResponse> created = new ArrayList<>();
        bySupplier.forEach((supplierId, lines) -> {
            PurchaseOrderRequest po = new PurchaseOrderRequest();
            po.setSupplierId(supplierId);
            po.setWarehouseId(req.getWarehouseId());
            po.setNotes("Raised from the reorder list");
            po.setLines(lines.stream().map(l -> {
                PurchaseOrderRequest.Line pl = new PurchaseOrderRequest.Line();
                pl.setProductId(l.getProductId());
                pl.setQuantity(l.getQuantity());
                return pl;
            }).toList());
            created.add(create(po));
        });
        return created;
    }

    /** What the order would look like (defaults, VAT, totals) without saving it; for the form. */
    @Transactional
    public PurchaseOrderResponse preview(PurchaseOrderRequest req) {
        return toResponse(build(new PurchaseOrder(), req, new ArrayList<>()));
    }

    /** A draft can be changed until it is placed with the supplier. */
    @Transactional
    public PurchaseOrderResponse update(Long id, PurchaseOrderRequest req) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw new IllegalStateException(po.getOrderNumber() + " is " + po.getStatus() + ": only drafts can be changed");
        }
        List<Map<String, Object>> taxOverrides = new ArrayList<>();
        po.getLines().clear();
        po.getCosts().clear();
        build(po, req, taxOverrides);
        if (req.isOrdered()) {
            po.setStatus(PurchaseOrder.Status.ORDERED);
            po.setOrderedAt(LocalDateTime.now());
        }
        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        Map<String, Object> audit = new LinkedHashMap<>(Map.of("lines", po.getLines().size(), "status", po.getStatus().name()));
        if (!taxOverrides.isEmpty()) audit.put("taxOverrides", taxOverrides);
        auditService.log("PURCHASE_ORDER", id, "UPDATE", audit);
        return saved;
    }

    /** Placed with the supplier (Cin7's Authorise); no approval step (decision 1). */
    @Transactional
    public PurchaseOrderResponse markOrdered(Long id) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw new IllegalStateException(po.getOrderNumber() + " is already " + po.getStatus());
        }
        po.setStatus(PurchaseOrder.Status.ORDERED);
        po.setOrderedAt(LocalDateTime.now());
        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        auditService.log("PURCHASE_ORDER", id, "ORDER", Map.of("status", "ORDERED"));
        return saved;
    }

    private PurchaseOrder build(PurchaseOrder po, PurchaseOrderRequest req, List<Map<String, Object>> taxOverrides) {
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));
        Supplier supplier = null;
        if (req.getSupplierId() != null) {
            supplier = supplierRepository.findById(req.getSupplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + req.getSupplierId()));
        } else if (req.getSupplierName() == null || req.getSupplierName().isBlank()) {
            throw new IllegalArgumentException("Choose a supplier");
        }
        po.setSupplier(supplier);
        po.setSupplierName(supplier != null ? supplier.getName() : req.getSupplierName().trim());
        po.setWarehouse(warehouse);
        po.setNotes(blankToNull(req.getNotes()));
        po.setSupplierReference(blankToNull(req.getSupplierReference()));
        po.setPaymentTerm(blankToNull(req.getPaymentTerm()) != null ? req.getPaymentTerm().trim()
                : supplier != null ? blankToNull(supplier.getPaymentTerm()) : null);
        po.setRequiredBy(req.getRequiredBy());

        for (PurchaseOrderRequest.Line l : req.getLines()) {
            Product product = productService.getProduct(l.getProductId());
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrder(po);
            line.setProduct(product);
            line.setQuantity(l.getQuantity());
            // From the product's own supplier: their price and code (as in Cin7); otherwise the cost price
            boolean ownSupplier = product.getDefaultSupplier() == null || supplier == null
                    || product.getDefaultSupplier().getId().equals(supplier.getId());
            BigDecimal supplierPrice = product.getDetails() != null ? product.getDetails().getSupplierPrice() : null;
            line.setUnitCost(l.getUnitCost() != null ? l.getUnitCost()
                    : ownSupplier && supplierPrice != null ? supplierPrice : product.getCostPrice());
            line.setDiscountPercent(l.getDiscountPercent() != null ? l.getDiscountPercent() : BigDecimal.ZERO);
            line.setSupplierSku(blankToNull(l.getSupplierSku()) != null ? l.getSupplierSku().trim()
                    : ownSupplier ? blankToNull(product.getSupplierProductCode()) : null);
            TaxService.Choice tax = taxService.choose(TaxRate.Usage.PURCHASE, l.getTaxRule(), l.getTaxReason(),
                    taxService.resolvePurchase(product, supplier), product.getSku(), taxOverrides);
            line.setTaxName(tax.rate().getName());
            line.setTaxRate(tax.rate().getRate());
            line.setTaxOverridden(tax.overridden());
            po.getLines().add(line);
        }
        if (req.getCosts() != null) {
            for (PurchaseOrderRequest.Cost c : req.getCosts()) {
                PurchaseOrderCost cost = new PurchaseOrderCost();
                cost.setPurchaseOrder(po);
                cost.setDescription(c.getDescription().trim());
                cost.setReference(blankToNull(c.getReference()));
                cost.setQuantity(c.getQuantity() != null ? c.getQuantity() : BigDecimal.ONE);
                cost.setUnitPrice(c.getUnitPrice());
                cost.setDiscountPercent(c.getDiscountPercent() != null ? c.getDiscountPercent() : BigDecimal.ZERO);
                TaxService.Choice tax = taxService.choose(TaxRate.Usage.PURCHASE, c.getTaxRule(), c.getTaxReason(),
                        taxService.resolvePurchase(null, supplier), cost.getDescription(), taxOverrides);
                cost.setTaxName(tax.rate().getName());
                cost.setTaxRate(tax.rate().getRate());
                po.getCosts().add(cost);
            }
        }
        return po;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /** Receives everything still outstanding. */
    @Transactional
    public PurchaseOrderResponse receive(Long id) {
        return receive(id, new PurchaseReceiptRequest());
    }

    /**
     * Goods received against the order (Cin7's stock receiving): several receipts per order, each line's stock comes in
     * at the line's cost after discount. More than outstanding is accepted and flagged (decision 2). Once nothing is
     * outstanding the order is RECEIVED.
     */
    @Transactional
    public PurchaseOrderResponse receive(Long id, PurchaseReceiptRequest req) {
        PurchaseOrder po = poRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + id));
        String key = blankToNull(req.getIdempotencyKey());
        if (key != null) {
            Optional<PurchaseReceipt> previous = receiptRepository.findByIdempotencyKey(key);
            if (previous.isPresent()) {
                if (!previous.get().getPurchaseOrder().getId().equals(id)) {
                    throw new IllegalArgumentException("This request key was already used for another purchase order");
                }
                return toDetailResponse(po);
            }
        }
        if (po.getStatus() != PurchaseOrder.Status.DRAFT && po.getStatus() != PurchaseOrder.Status.ORDERED) {
            throw new IllegalStateException(po.getOrderNumber() + " is " + po.getStatus() + ": nothing more to receive");
        }
        Warehouse warehouse = req.getWarehouseId() == null ? po.getWarehouse()
                : warehouseRepository.findById(req.getWarehouseId())
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));

        // What arrives: the given lines, or everything outstanding
        List<LineQty> incoming = new ArrayList<>();
        if (req.getLines() == null || req.getLines().isEmpty()) {
            po.getLines().stream().filter(l -> l.getOutstandingQuantity().signum() > 0)
                    .forEach(l -> incoming.add(new LineQty(l, l.getOutstandingQuantity())));
            if (incoming.isEmpty()) throw new IllegalStateException("Nothing is outstanding on " + po.getOrderNumber());
        } else {
            Set<Long> seen = new HashSet<>();
            for (PurchaseReceiptRequest.Line rl : req.getLines()) {
                PurchaseOrderLine line = po.getLines().stream().filter(l -> l.getId().equals(rl.getPurchaseOrderLineId())).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Line " + rl.getPurchaseOrderLineId() + " is not on " + po.getOrderNumber()));
                if (!seen.add(line.getId())) throw new IllegalArgumentException(line.getProduct().getSku() + " appears more than once");
                incoming.add(new LineQty(line, rl.getQuantity()));
            }
        }
        movementService.lockProducts(incoming.stream().map(x -> x.line().getProduct().getId()).toList());

        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setPurchaseOrder(po);
        receipt.setWarehouse(warehouse);
        receipt.setReceivedOn(req.getReceivedOn() != null ? req.getReceivedOn() : LocalDate.now());
        receipt.setNotes(blankToNull(req.getNotes()));
        receipt.setIdempotencyKey(key);
        receipt.setCreatedBy(auditService.currentActorName());
        List<Map<String, Object>> over = new ArrayList<>();
        for (LineQty x : incoming) {
            PurchaseOrderLine line = x.line();
            BigDecimal overQty = x.qty().subtract(line.getOutstandingQuantity()).max(BigDecimal.ZERO);
            PurchaseReceiptLine rl = new PurchaseReceiptLine();
            rl.setReceipt(receipt);
            rl.setPurchaseOrderLine(line);
            rl.setQuantity(x.qty());
            rl.setOverQuantity(overQty);
            receipt.getLines().add(rl);
            line.setReceivedQuantity(line.getReceivedQuantity().add(x.qty()));
            if (overQty.signum() > 0) over.add(Map.of("sku", line.getProduct().getSku(), "over", overQty));
            // The PO line cost after discount becomes the cost of the stock received (FIFO)
            movementService.createMovement(line.getProduct(), warehouse, x.qty(),
                    MovementType.PURCHASE_IN, ReferenceType.PURCHASE_ORDER,
                    po.getOrderNumber(), "Received on " + po.getOrderNumber())
                    .setUnitCost(netUnitCost(line));
        }
        receiptRepository.save(receipt);

        if (po.getOrderedAt() == null) po.setOrderedAt(LocalDateTime.now());
        if (po.getStatus() == PurchaseOrder.Status.DRAFT) po.setStatus(PurchaseOrder.Status.ORDERED);
        if (po.getLines().stream().allMatch(l -> l.getOutstandingQuantity().signum() == 0)) {
            po.setStatus(PurchaseOrder.Status.RECEIVED);
            po.setReceivedAt(LocalDateTime.now());
        }
        poRepository.save(po);
        Map<String, Object> audit = new LinkedHashMap<>(Map.of("receipt", receipt.getId(), "lines", receipt.getLines().size(),
                "status", po.getStatus().name()));
        if (!over.isEmpty()) audit.put("overDelivered", over);
        auditService.log("PURCHASE_ORDER", id, "RECEIVE", audit);
        return toDetailResponse(po);
    }

    /** Nothing more is coming: what is outstanding is closed short; the order is RECEIVED (or CANCELLED if nothing came). */
    @Transactional
    public PurchaseOrderResponse closeShort(Long id) {
        PurchaseOrder po = poRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + id));
        if (po.getStatus() != PurchaseOrder.Status.ORDERED) {
            throw new IllegalStateException("Only an ordered purchase order can be closed short (" + po.getOrderNumber() + " is " + po.getStatus() + ")");
        }
        boolean anythingReceived = po.getLines().stream().anyMatch(l -> l.getReceivedQuantity().signum() > 0);
        if (!anythingReceived) throw new IllegalStateException("Nothing has been received on " + po.getOrderNumber() + ": cancel it instead");
        List<Map<String, Object>> closed = new ArrayList<>();
        for (PurchaseOrderLine l : po.getLines()) {
            BigDecimal outstanding = l.getOutstandingQuantity();
            if (outstanding.signum() > 0) {
                l.setClosedShortQuantity(l.getClosedShortQuantity().add(outstanding));
                closed.add(Map.of("sku", l.getProduct().getSku(), "short", outstanding));
            }
        }
        po.setStatus(PurchaseOrder.Status.RECEIVED);
        po.setReceivedAt(LocalDateTime.now());
        poRepository.save(po);
        auditService.log("PURCHASE_ORDER", id, "CLOSE_SHORT", Map.of("closedShort", closed));
        return toDetailResponse(po);
    }

    private static BigDecimal netUnitCost(PurchaseOrderLine line) {
        if (line.getUnitCost() == null) return null;
        BigDecimal discount = line.getDiscountPercent() == null ? BigDecimal.ZERO : line.getDiscountPercent();
        return line.getUnitCost().multiply(BigDecimal.valueOf(100).subtract(discount))
                .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP);
    }

    // Entities use Lombok @Data (hashCode walks the order<->line graph), so never use them as map keys
    private record LineQty(PurchaseOrderLine line, BigDecimal qty) {}

    @Transactional
    public PurchaseOrderResponse cancel(Long id) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT && po.getStatus() != PurchaseOrder.Status.ORDERED) {
            throw new IllegalStateException("Only draft or ordered purchase orders can be cancelled");
        }
        if (po.getLines().stream().anyMatch(l -> l.getReceivedQuantity().signum() > 0)) {
            throw new IllegalStateException("Stock has been received on " + po.getOrderNumber() + ": close the rest short instead");
        }
        if (po.getLines().stream().anyMatch(l -> l.getBilledQuantity().signum() > 0)) {
            throw new IllegalStateException(po.getOrderNumber() + " has supplier bills: cancel them first");
        }
        po.setStatus(PurchaseOrder.Status.CANCELLED);
        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        auditService.log("PURCHASE_ORDER", id, "CANCEL", Map.of("status", "CANCELLED"));
        return saved;
    }

    private PurchaseOrder getPO(Long id) {
        return poRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + id));
    }

    private PurchaseOrderResponse toResponse(PurchaseOrder po) {
        PurchaseOrderResponse r = new PurchaseOrderResponse();
        r.setId(po.getId());
        r.setOrderNumber(po.getOrderNumber());
        r.setSupplierId(po.getSupplier() != null ? po.getSupplier().getId() : null);
        r.setSupplierName(po.getSupplierName());
        r.setSupplierReference(po.getSupplierReference());
        r.setPaymentTerm(po.getPaymentTerm());
        r.setRequiredBy(po.getRequiredBy());
        r.setWarehouseId(po.getWarehouse().getId());
        r.setWarehouseName(po.getWarehouse().getName());
        r.setStatus(po.getStatus());
        r.setNotes(po.getNotes());
        r.setCreatedAt(po.getCreatedAt());
        r.setOrderedAt(po.getOrderedAt());
        r.setReceivedAt(po.getReceivedAt());
        BigDecimal[] lines = { BigDecimal.ZERO, BigDecimal.ZERO };
        r.setLines(po.getLines().stream().map(l -> {
            PurchaseOrderResponse.Line lr = new PurchaseOrderResponse.Line();
            lr.setId(l.getId());
            lr.setProductId(l.getProduct().getId());
            lr.setProductSku(l.getProduct().getSku());
            lr.setProductName(l.getProduct().getName());
            lr.setUnit(l.getProduct().getUnit());
            lr.setSupplierSku(l.getSupplierSku());
            lr.setQuantity(l.getQuantity());
            lr.setUnitCost(l.getUnitCost());
            lr.setDiscountPercent(l.getDiscountPercent());
            lr.setTaxName(l.getTaxName());
            lr.setTaxRate(l.getTaxRate());
            lr.setTaxOverridden(l.isTaxOverridden());
            BigDecimal net = SaleAmounts.net(l.getQuantity(), l.getUnitCost(), l.getDiscountPercent());
            BigDecimal tax = SaleAmounts.tax(net, l.getTaxRate());
            lr.setNetAmount(net);
            lr.setTaxAmount(tax);
            lr.setTotalAmount(net.add(tax));
            lr.setReceivedQuantity(l.getReceivedQuantity());
            lr.setBilledQuantity(l.getBilledQuantity());
            lr.setClosedShortQuantity(l.getClosedShortQuantity());
            lr.setOutstandingQuantity(l.getOutstandingQuantity());
            lr.setOverQuantity(l.getOverQuantity());
            lines[0] = lines[0].add(net);
            lines[1] = lines[1].add(tax);
            return lr;
        }).collect(Collectors.toList()));
        BigDecimal[] costs = { BigDecimal.ZERO, BigDecimal.ZERO };
        r.setCosts(po.getCosts().stream().map(c -> {
            PurchaseOrderResponse.Cost cr = new PurchaseOrderResponse.Cost();
            cr.setId(c.getId());
            cr.setDescription(c.getDescription());
            cr.setReference(c.getReference());
            cr.setQuantity(c.getQuantity());
            cr.setUnitPrice(c.getUnitPrice());
            cr.setDiscountPercent(c.getDiscountPercent());
            cr.setTaxName(c.getTaxName());
            cr.setTaxRate(c.getTaxRate());
            BigDecimal net = SaleAmounts.net(c.getQuantity(), c.getUnitPrice(), c.getDiscountPercent());
            BigDecimal tax = SaleAmounts.tax(net, c.getTaxRate());
            cr.setNetAmount(net);
            cr.setTaxAmount(tax);
            cr.setTotalAmount(net.add(tax));
            costs[0] = costs[0].add(net);
            costs[1] = costs[1].add(tax);
            return cr;
        }).collect(Collectors.toList()));
        r.setLinesNet(lines[0]);
        r.setLinesTax(lines[1]);
        r.setCostsNet(costs[0]);
        r.setCostsTax(costs[1]);
        r.setSubtotal(lines[0].add(costs[0]));
        r.setTaxTotal(lines[1].add(costs[1]));
        r.setTotal(r.getSubtotal().add(r.getTaxTotal()));
        boolean anyIn = po.getLines().stream().anyMatch(l -> l.getReceivedQuantity().signum() > 0);
        boolean allIn = !po.getLines().isEmpty() && po.getLines().stream().allMatch(l -> l.getOutstandingQuantity().signum() == 0);
        r.setReceivingStatus(po.getStatus() == PurchaseOrder.Status.RECEIVED || (allIn && anyIn) ? "RECEIVED"
                : anyIn ? "PARTIALLY_RECEIVED" : "NOT_RECEIVED");
        r.setOverDelivered(po.getLines().stream().anyMatch(l -> l.getOverQuantity().signum() > 0));
        // Billed in full when every line's bills cover what came in (or is still expected)
        boolean anyBilled = po.getLines().stream().anyMatch(l -> l.getBilledQuantity().signum() > 0);
        boolean allBilled = anyBilled && po.getLines().stream().allMatch(l -> l.getToBillQuantity().signum() == 0);
        r.setBillingStatus(allBilled ? "BILLED" : anyBilled ? "PARTIALLY_BILLED" : "NOT_BILLED");
        r.setClosedShort(po.getLines().stream().anyMatch(l -> l.getClosedShortQuantity().signum() > 0));
        return r;
    }
}
