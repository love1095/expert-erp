package com.experterp.backend.service;

import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.PurchaseOrderRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    public Page<PurchaseOrderResponse> findAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return poRepository.findAll(pageable).map(this::toResponse);
    }

    public PurchaseOrderResponse findById(Long id) {
        return toResponse(getPO(id));
    }

    @Transactional
    public PurchaseOrderResponse create(PurchaseOrderRequest req) {
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));

        PurchaseOrder po = new PurchaseOrder();
        po.setSupplierName(req.getSupplierName());
        po.setWarehouse(warehouse);
        po.setNotes(req.getNotes());

        for (PurchaseOrderRequest.Line l : req.getLines()) {
            Product product = productService.getProduct(l.getProductId());
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrder(po);
            line.setProduct(product);
            line.setQuantity(l.getQuantity());
            line.setUnitCost(l.getUnitCost());
            po.getLines().add(line);
        }

        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        auditService.log("PURCHASE_ORDER", saved.getId(), "CREATE",
                Map.of("supplier", po.getSupplierName(), "lines", po.getLines().size()));
        return saved;
    }

    @Transactional
    public PurchaseOrderResponse receive(Long id) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw new IllegalStateException("Purchase order is already " + po.getStatus());
        }

        for (PurchaseOrderLine line : po.getLines()) {
            // The PO line cost becomes the cost of the stock received (FIFO)
            movementService.createMovement(
                    line.getProduct(), po.getWarehouse(), line.getQuantity(),
                    MovementType.PURCHASE_IN, ReferenceType.PURCHASE_ORDER,
                    "PO-" + po.getId(), "Received on PO #" + po.getId())
                    .setUnitCost(line.getUnitCost());
        }

        po.setStatus(PurchaseOrder.Status.RECEIVED);
        po.setReceivedAt(LocalDateTime.now());
        PurchaseOrderResponse saved = toResponse(poRepository.save(po));
        auditService.log("PURCHASE_ORDER", id, "RECEIVE", Map.of("status", "RECEIVED"));
        return saved;
    }

    @Transactional
    public PurchaseOrderResponse cancel(Long id) {
        PurchaseOrder po = getPO(id);
        if (po.getStatus() != PurchaseOrder.Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be cancelled");
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
        r.setSupplierName(po.getSupplierName());
        r.setWarehouseId(po.getWarehouse().getId());
        r.setWarehouseName(po.getWarehouse().getName());
        r.setStatus(po.getStatus());
        r.setNotes(po.getNotes());
        r.setCreatedAt(po.getCreatedAt());
        r.setReceivedAt(po.getReceivedAt());
        r.setLines(po.getLines().stream().map(l -> {
            PurchaseOrderResponse.Line lr = new PurchaseOrderResponse.Line();
            lr.setId(l.getId());
            lr.setProductId(l.getProduct().getId());
            lr.setProductSku(l.getProduct().getSku());
            lr.setProductName(l.getProduct().getName());
            lr.setQuantity(l.getQuantity());
            lr.setUnitCost(l.getUnitCost());
            return lr;
        }).collect(Collectors.toList()));
        return r;
    }
}
