package com.experterp.backend.service;

import com.experterp.backend.dto.ProductRequest;
import com.experterp.backend.dto.ProductResponse;
import com.experterp.backend.entity.Category;
import com.experterp.backend.entity.Product;
import com.experterp.backend.exception.DuplicateResourceException;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CategoryRepository;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.SupplierRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AllocationService allocationService;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final SubscriptionService subscriptionService;

    /**
     * One page of products, newest first. Long texts (description, Cin7 details) are left out of lists:
     * with thousands of Cin7 products they would make the response huge. {@link #findById} has everything.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> findAll(Long categoryId, Product.Status status, String q, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        String pattern = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        Page<Product> products = productRepository.search(categoryId, status, pattern, pageable);

        // Stock for the whole page in one query
        Map<Long, BigDecimal> stock = new HashMap<>();
        List<Long> ids = products.getContent().stream().map(Product::getId).toList();
        if (!ids.isEmpty()) {
            for (Object[] row : stockMovementRepository.calculateTotalStocks(ids)) {
                stock.put((Long) row[0], (BigDecimal) row[1]);
            }
        }
        Map<Long, BigDecimal> reserved = allocationService.reservedByProduct(ids);
        return products.map(p -> {
            ProductResponse r = toResponse(p, stock.getOrDefault(p.getId(), BigDecimal.ZERO));
            withReserved(r, reserved.getOrDefault(p.getId(), BigDecimal.ZERO));
            r.setDescription(null);
            r.setDetails(null);
            return r;
        });
    }

    /** For dropdowns: every product of a status (null = all), sorted by SKU, with its total stock. */
    public record ProductOption(Long id, String sku, String name, String unit, Long categoryId, BigDecimal totalStock) {}

    @Transactional(readOnly = true)
    public List<ProductOption> options(Product.Status status) {
        Map<Long, BigDecimal> stock = new HashMap<>();
        for (Object[] row : stockMovementRepository.calculateAllTotalStocks()) {
            stock.put((Long) row[0], (BigDecimal) row[1]);
        }
        return productRepository.findOptions(status).stream()
                .map(r -> new ProductOption((Long) r[0], (String) r[1], (String) r[2], (String) r[3], (Long) r[4],
                        stock.getOrDefault((Long) r[0], BigDecimal.ZERO)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProductResponse findById(Long id) {
        Product p = getProduct(id);
        ProductResponse single = toResponse(p, stockMovementRepository.calculateTotalStock(p.getId()));
        withReserved(single, allocationService.reservedByProduct(List.of(p.getId())).getOrDefault(p.getId(), BigDecimal.ZERO));
        return single;
    }

    @Transactional
    public ProductResponse create(ProductRequest req) {
        subscriptionService.checkProductLimit(TenantContext.get());
        if (productRepository.existsBySku(req.getSku())) {
            throw new DuplicateResourceException("SKU already exists: " + req.getSku());
        }
        Product p = new Product();
        applyRequest(p, req);
        p = productRepository.save(p);
        return toResponse(p, BigDecimal.ZERO);
    }

    @Transactional
    public ProductResponse update(Long id, ProductRequest req) {
        Product p = getProduct(id);
        productRepository.findBySku(req.getSku()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new DuplicateResourceException("SKU already in use: " + req.getSku());
            }
        });
        applyRequest(p, req);
        p.setUpdatedAt(LocalDateTime.now());
        p = productRepository.save(p);
        ProductResponse single = toResponse(p, stockMovementRepository.calculateTotalStock(p.getId()));
        withReserved(single, allocationService.reservedByProduct(List.of(p.getId())).getOrDefault(p.getId(), BigDecimal.ZERO));
        return single;
    }

    public void delete(Long id) {
        getProduct(id);
        productRepository.deleteById(id);
    }

    public Product getProduct(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    private void applyRequest(Product p, ProductRequest req) {
        p.setSku(req.getSku().trim());
        p.setName(req.getName().trim());
        p.setDescription(req.getDescription());
        p.setShortDescription(req.getShortDescription());
        p.setUnit(req.getUnit().trim());
        p.setStatus(req.getStatus() == null ? Product.Status.ACTIVE : req.getStatus());
        p.setType(req.getType() == null ? Product.Type.STOCK : req.getType());
        p.setSellable(req.getSellable() == null || req.getSellable());
        p.setBrand(req.getBrand());
        p.setBarcode(req.getBarcode());
        p.setCostPrice(req.getCostPrice());
        p.setSellPrice(req.getSellPrice());
        p.setTradePrice(req.getTradePrice());
        p.setMinStock(req.getMinStock());
        p.setReorderQuantity(req.getReorderQuantity());
        p.setSupplierProductCode(req.getSupplierProductCode());
        p.setFamilySku(blankToNull(req.getFamilySku()));
        p.setFamilyName(req.getFamilyName());
        p.setVariantOptions(req.getVariantOptions() == null || req.getVariantOptions().isEmpty() ? null : req.getVariantOptions());
        if (req.getDetails() != null) p.setDetails(req.getDetails());

        if (req.getCategoryId() != null) {
            Category cat = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + req.getCategoryId()));
            p.setCategory(cat);
        } else {
            p.setCategory(null);
        }
        p.setDefaultSupplier(req.getDefaultSupplierId() == null ? null : supplierRepository.findById(req.getDefaultSupplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + req.getDefaultSupplierId())));
    }

    private static void withReserved(ProductResponse r, BigDecimal reserved) {
        r.setReservedStock(reserved);
        r.setAvailableStock(r.getTotalStock().subtract(reserved));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private ProductResponse toResponse(Product p, BigDecimal totalStock) {
        ProductResponse r = new ProductResponse();
        r.setId(p.getId());
        r.setSku(p.getSku());
        r.setName(p.getName());
        r.setDescription(p.getDescription());
        r.setShortDescription(p.getShortDescription());
        r.setUnit(p.getUnit());
        r.setStatus(p.getStatus());
        r.setType(p.getType());
        r.setSellable(p.isSellable());
        r.setBrand(p.getBrand());
        r.setBarcode(p.getBarcode());
        r.setCostPrice(p.getCostPrice());
        r.setSellPrice(p.getSellPrice());
        r.setTradePrice(p.getTradePrice());
        r.setMinStock(p.getMinStock());
        r.setReorderQuantity(p.getReorderQuantity());
        r.setSupplierProductCode(p.getSupplierProductCode());
        r.setFamilySku(p.getFamilySku());
        r.setFamilyName(p.getFamilyName());
        r.setVariantOptions(p.getVariantOptions());
        r.setDetails(p.getDetails());
        r.setCreatedAt(p.getCreatedAt());
        r.setUpdatedAt(p.getUpdatedAt());
        r.setTotalStock(totalStock);
        r.setAvailableStock(totalStock);
        if (p.getCategory() != null) {
            r.setCategoryId(p.getCategory().getId());
            r.setCategoryName(p.getCategory().getName());
        }
        if (p.getDefaultSupplier() != null) {
            r.setDefaultSupplierId(p.getDefaultSupplier().getId());
            r.setDefaultSupplierName(p.getDefaultSupplier().getName());
        }
        r.setLowStock(p.getMinStock() != null && totalStock.compareTo(p.getMinStock()) <= 0);
        return r;
    }
}
