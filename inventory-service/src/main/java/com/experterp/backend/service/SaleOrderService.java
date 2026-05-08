package com.experterp.backend.service;

import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.SaleOrderRepository;
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
public class SaleOrderService {

    private final SaleOrderRepository soRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductService productService;
    private final StockMovementService movementService;
    private final AuditService auditService;

    public Page<SaleOrderResponse> findAll(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return soRepository.findAll(pageable).map(this::toResponse);
    }

    public SaleOrderResponse findById(Long id) {
        return toResponse(getSO(id));
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
    public SaleOrderResponse fulfill(Long id) {
        SaleOrder so = getSO(id);
        if (so.getStatus() != SaleOrder.Status.DRAFT) {
            throw new IllegalStateException("Sale order is already " + so.getStatus());
        }

        // Validate all lines first before creating any movements
        for (SaleOrderLine line : so.getLines()) {
            movementService.assertSufficientStock(
                    line.getProduct().getId(), so.getWarehouse().getId(), line.getQuantity());
        }

        for (SaleOrderLine line : so.getLines()) {
            movementService.createMovement(
                    line.getProduct(), so.getWarehouse(), line.getQuantity(),
                    MovementType.SALE_OUT, ReferenceType.SALE_ORDER,
                    "SO-" + so.getId(), "Fulfilled on SO #" + so.getId());
        }

        so.setStatus(SaleOrder.Status.FULFILLED);
        so.setFulfilledAt(LocalDateTime.now());
        SaleOrderResponse saved = toResponse(soRepository.save(so));
        auditService.log("SALE_ORDER", id, "FULFILL", Map.of("status", "FULFILLED"));
        return saved;
    }

    @Transactional
    public SaleOrderResponse cancel(Long id) {
        SaleOrder so = getSO(id);
        if (so.getStatus() != SaleOrder.Status.DRAFT) {
            throw new IllegalStateException("Only DRAFT orders can be cancelled");
        }
        so.setStatus(SaleOrder.Status.CANCELLED);
        SaleOrderResponse saved = toResponse(soRepository.save(so));
        auditService.log("SALE_ORDER", id, "CANCEL", Map.of("status", "CANCELLED"));
        return saved;
    }

    private SaleOrder getSO(Long id) {
        return soRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale order not found: " + id));
    }

    private SaleOrderResponse toResponse(SaleOrder so) {
        SaleOrderResponse r = new SaleOrderResponse();
        r.setId(so.getId());
        r.setCustomerName(so.getCustomerName());
        r.setWarehouseId(so.getWarehouse().getId());
        r.setWarehouseName(so.getWarehouse().getName());
        r.setStatus(so.getStatus());
        r.setNotes(so.getNotes());
        r.setCreatedAt(so.getCreatedAt());
        r.setFulfilledAt(so.getFulfilledAt());
        r.setLines(so.getLines().stream().map(l -> {
            SaleOrderResponse.Line lr = new SaleOrderResponse.Line();
            lr.setId(l.getId());
            lr.setProductId(l.getProduct().getId());
            lr.setProductSku(l.getProduct().getSku());
            lr.setProductName(l.getProduct().getName());
            lr.setQuantity(l.getQuantity());
            lr.setUnitPrice(l.getUnitPrice());
            return lr;
        }).collect(Collectors.toList()));
        return r;
    }
}
