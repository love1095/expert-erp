package com.experterp.backend.service;

import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.PurchaseOrderRepository;
import com.experterp.backend.repository.SupplierRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> findAll(int page, int size, PurchaseOrder.Status status, String q) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return poRepository.search(status, Search.pattern(q), pageable).map(this::toResponse);
    }

    public PurchaseOrderResponse findById(Long id) {
        return toResponse(getPO(id));
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

    @Transactional
    public PurchaseOrderResponse receive(Long id) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT && po.getStatus() != PurchaseOrder.Status.ORDERED) {
            throw new IllegalStateException(po.getOrderNumber() + " is already " + po.getStatus());
        }

        for (PurchaseOrderLine line : po.getLines()) {
            // The PO line cost becomes the cost of the stock received (FIFO)
            movementService.createMovement(
                    line.getProduct(), po.getWarehouse(), line.getQuantity(),
                    MovementType.PURCHASE_IN, ReferenceType.PURCHASE_ORDER,
                    po.getOrderNumber(), "Received on " + po.getOrderNumber())
                    .setUnitCost(line.getUnitCost());
        }

        po.setStatus(PurchaseOrder.Status.RECEIVED);
        if (po.getOrderedAt() == null) po.setOrderedAt(LocalDateTime.now());
        po.setReceivedAt(LocalDateTime.now());
        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        auditService.log("PURCHASE_ORDER", id, "RECEIVE", Map.of("status", "RECEIVED"));
        return saved;
    }

    @Transactional
    public PurchaseOrderResponse cancel(Long id) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT && po.getStatus() != PurchaseOrder.Status.ORDERED) {
            throw new IllegalStateException("Only draft or ordered purchase orders can be cancelled");
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
        return r;
    }
}
