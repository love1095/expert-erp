package com.experterp.backend.service;

import com.experterp.backend.dto.StockLevelResponse;
import com.experterp.backend.dto.StockMovementRequest;
import com.experterp.backend.dto.StockMovementResponse;
import com.experterp.backend.dto.StockTransferRequest;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.StockMovement;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.event.StockChangedEvent;
import com.experterp.backend.exception.InsufficientStockException;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockMovementService {

    private final StockMovementRepository movementRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditService auditService;

    @Transactional
    public StockMovementResponse create(StockMovementRequest req) {
        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + req.getProductId()));
        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getWarehouseId()));

        if (!req.getType().isIn()) {
            assertSufficientStock(req.getProductId(), req.getWarehouseId(), req.getQuantity());
        }

        StockMovement m = buildMovement(product, warehouse, req.getQuantity(), req.getType(),
                req.getReferenceType(), req.getReferenceId(), req.getNotes());
        return toResponse(movementRepository.save(m));
    }

    @Transactional
    public List<StockMovementResponse> transfer(StockTransferRequest req) {
        if (req.getFromWarehouseId().equals(req.getToWarehouseId())) {
            throw new IllegalArgumentException("Source and destination warehouse must be different");
        }
        Product product = productRepository.findById(req.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + req.getProductId()));
        Warehouse from = warehouseRepository.findById(req.getFromWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getFromWarehouseId()));
        Warehouse to = warehouseRepository.findById(req.getToWarehouseId())
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + req.getToWarehouseId()));

        assertSufficientStock(req.getProductId(), req.getFromWarehouseId(), req.getQuantity());

        String transferRef = "TRF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        StockMovement out = buildMovement(product, from, req.getQuantity(),
                MovementType.TRANSFER_OUT, ReferenceType.TRANSFER, transferRef, req.getNotes());
        StockMovement in = buildMovement(product, to, req.getQuantity(),
                MovementType.TRANSFER_IN, ReferenceType.TRANSFER, transferRef, req.getNotes());

        List<StockMovement> saved = movementRepository.saveAll(Arrays.asList(out, in));
        return saved.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public Page<StockMovementResponse> findAll(Long productId, Long warehouseId, Long categoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<StockMovement> movements;
        if (productId != null && warehouseId != null) {
            movements = movementRepository.findByProductIdAndWarehouseId(productId, warehouseId, pageable);
        } else if (productId != null) {
            movements = movementRepository.findByProductId(productId, pageable);
        } else if (warehouseId != null) {
            movements = movementRepository.findByWarehouseId(warehouseId, pageable);
        } else if (categoryId != null) {
            movements = movementRepository.findByProduct_Category_Id(categoryId, pageable);
        } else {
            movements = movementRepository.findAll(pageable);
        }
        return movements.map(this::toResponse);
    }

    public List<StockMovementResponse> findAllForExport(Long productId, Long warehouseId) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        List<StockMovement> movements;
        if (productId != null && warehouseId != null) {
            movements = movementRepository.findByProductIdAndWarehouseId(productId, warehouseId, PageRequest.of(0, Integer.MAX_VALUE / 2, sort)).getContent();
        } else if (productId != null) {
            movements = movementRepository.findByProductId(productId, PageRequest.of(0, Integer.MAX_VALUE / 2, sort)).getContent();
        } else if (warehouseId != null) {
            movements = movementRepository.findByWarehouseId(warehouseId, PageRequest.of(0, Integer.MAX_VALUE / 2, sort)).getContent();
        } else {
            movements = movementRepository.findAll(sort);
        }
        return movements.stream().map(this::toResponse).collect(Collectors.toList());
    }

    public StockLevelResponse getStockLevel(Long productId, Long warehouseId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        Warehouse warehouse = warehouseRepository.findById(warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));
        BigDecimal stock = movementRepository.calculateStock(productId, warehouseId);
        Long categoryId = product.getCategory() != null ? product.getCategory().getId() : null;
        String categoryName = product.getCategory() != null ? product.getCategory().getName() : null;
        return buildStockLevel(productId, product.getSku(), product.getName(),
                product.getUnit(), product.getMinStock(),
                warehouseId, warehouse.getName(), categoryId, categoryName, stock);
    }

    // Single aggregation query — no N+1
    public List<StockLevelResponse> getStockOverview() {
        Long tenantId = com.experterp.backend.multitenancy.TenantContext.get();
        return movementRepository.getStockOverviewRaw(tenantId).stream()
                .map(this::mapRow)
                .collect(Collectors.toList());
    }

    public Page<StockLevelResponse> getStockOverviewPage(int page, int size, Long categoryId) {
        Long tenantId = com.experterp.backend.multitenancy.TenantContext.get();
        Pageable pageable = PageRequest.of(page, size);
        if (categoryId != null) {
            return movementRepository.getStockOverviewPagedByCategory(tenantId, categoryId, pageable)
                    .map(this::mapRow);
        }
        return movementRepository.getStockOverviewPaged(tenantId, pageable)
                .map(this::mapRow);
    }

    // row: p.id[0], p.sku[1], p.name[2], p.unit[3], p.min_stock[4],
    //      w.id[5], w.name[6], c.id[7], c.name[8], stock[9]
    private StockLevelResponse mapRow(Object[] row) {
        return buildStockLevel(
                toLong(row[0]), str(row[1]), str(row[2]), str(row[3]),
                row[4] != null ? new BigDecimal(row[4].toString()) : null,
                toLong(row[5]), str(row[6]),
                row[7] != null ? toLong(row[7]) : null, str(row[8]),
                new BigDecimal(row[9].toString()));
    }

    // ---- helpers used by PurchaseOrderService / SaleOrderService ----

    @Transactional
    public StockMovement createMovement(Product product, Warehouse warehouse, BigDecimal qty,
                                        MovementType type, ReferenceType refType, String refId, String notes) {
        if (!type.isIn()) {
            assertSufficientStock(product.getId(), warehouse.getId(), qty);
        }
        StockMovement saved = movementRepository.save(buildMovement(product, warehouse, qty, type, refType, refId, notes));
        eventPublisher.publishEvent(new StockChangedEvent(this, saved.getProduct().getId(),
                saved.getWarehouse().getId(), saved.getQuantity(), saved.getType(), saved.getReferenceId()));
        auditService.log("STOCK_MOVEMENT", saved.getId(), "CREATE", Map.of(
                "type", saved.getType(),
                "qty", saved.getQuantity(),
                "product", saved.getProduct().getSku(),
                "warehouse", saved.getWarehouse().getName()));
        return saved;
    }

    public void assertSufficientStock(Long productId, Long warehouseId, BigDecimal required) {
        productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        BigDecimal available = movementRepository.calculateStock(productId, warehouseId);
        if (available.compareTo(required) < 0) {
            throw new InsufficientStockException(
                    String.format("Insufficient stock. Available: %s, Requested: %s", available, required));
        }
    }

    public String exportCsv(Long productId, Long warehouseId) {
        List<StockMovementResponse> list = findAllForExport(productId, warehouseId);
        StringBuilder sb = new StringBuilder("Date,Type,Product SKU,Product Name,Warehouse,Quantity,Ref Type,Ref ID,Notes\n");
        for (StockMovementResponse m : list) {
            sb.append(String.format("\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",%s,\"%s\",\"%s\",\"%s\"\n",
                m.getCreatedAt(), m.getType(), m.getProductSku(), m.getProductName(),
                m.getWarehouseName(), m.getQuantity(),
                m.getReferenceType() != null ? m.getReferenceType() : "",
                m.getReferenceId() != null ? m.getReferenceId() : "",
                m.getNotes() != null ? m.getNotes().replace("\"", "\"\"") : ""));
        }
        return sb.toString();
    }

    // ---- private ----

    private StockMovement buildMovement(Product product, Warehouse warehouse, BigDecimal qty,
                                        MovementType type, ReferenceType refType, String refId, String notes) {
        StockMovement m = new StockMovement();
        m.setProduct(product);
        m.setWarehouse(warehouse);
        m.setQuantity(qty);
        m.setType(type);
        m.setReferenceType(refType);
        m.setReferenceId(refId);
        m.setNotes(notes);
        return m;
    }

    private StockLevelResponse buildStockLevel(Long productId, String sku, String name, String unit,
                                               BigDecimal minStock, Long warehouseId, String warehouseName,
                                               Long categoryId, String categoryName, BigDecimal stock) {
        StockLevelResponse r = new StockLevelResponse();
        r.setProductId(productId);
        r.setProductSku(sku);
        r.setProductName(name);
        r.setUnit(unit);
        r.setMinStock(minStock);
        r.setWarehouseId(warehouseId);
        r.setWarehouseName(warehouseName);
        r.setCategoryId(categoryId);
        r.setCategoryName(categoryName);
        r.setStock(stock);
        r.setLowStock(minStock != null && stock.compareTo(minStock) <= 0);
        return r;
    }

    public StockMovementResponse toResponse(StockMovement m) {
        StockMovementResponse r = new StockMovementResponse();
        r.setId(m.getId());
        r.setProductId(m.getProduct().getId());
        r.setProductName(m.getProduct().getName());
        r.setProductSku(m.getProduct().getSku());
        if (m.getProduct().getCategory() != null) {
            r.setCategoryId(m.getProduct().getCategory().getId());
            r.setCategoryName(m.getProduct().getCategory().getName());
        }
        r.setWarehouseId(m.getWarehouse().getId());
        r.setWarehouseName(m.getWarehouse().getName());
        r.setQuantity(m.getQuantity());
        r.setType(m.getType());
        r.setReferenceType(m.getReferenceType());
        r.setReferenceId(m.getReferenceId());
        r.setNotes(m.getNotes());
        r.setCreatedAt(m.getCreatedAt());
        return r;
    }

    private Long toLong(Object o) { return ((Number) o).longValue(); }
    private String str(Object o)  { return o != null ? o.toString() : null; }
}
