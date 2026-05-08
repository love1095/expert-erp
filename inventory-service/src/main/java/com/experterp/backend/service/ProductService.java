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
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CategoryRepository categoryRepository;
    private final SubscriptionService subscriptionService;

    public Page<ProductResponse> findAll(Long categoryId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Product> products = categoryId != null
                ? productRepository.findByCategory_Id(categoryId, pageable)
                : productRepository.findAll(pageable);
        return products.map(this::toResponse);
    }

    public ProductResponse findById(Long id) {
        return toResponse(getProduct(id));
    }

    public ProductResponse create(ProductRequest req) {
        subscriptionService.checkProductLimit(TenantContext.get());
        if (productRepository.existsBySku(req.getSku())) {
            throw new DuplicateResourceException("SKU already exists: " + req.getSku());
        }
        Product p = new Product();
        applyRequest(p, req);
        return toResponse(productRepository.save(p));
    }

    public ProductResponse update(Long id, ProductRequest req) {
        Product p = getProduct(id);
        productRepository.findBySku(req.getSku()).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new DuplicateResourceException("SKU already in use: " + req.getSku());
            }
        });
        applyRequest(p, req);
        return toResponse(productRepository.save(p));
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
        p.setSku(req.getSku());
        p.setName(req.getName());
        p.setDescription(req.getDescription());
        p.setUnit(req.getUnit());
        p.setCostPrice(req.getCostPrice());
        p.setMinStock(req.getMinStock());

        if (req.getCategoryId() != null) {
            Category cat = categoryRepository.findById(req.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + req.getCategoryId()));
            p.setCategory(cat);
        } else {
            p.setCategory(null);
        }
    }

    private ProductResponse toResponse(Product p) {
        BigDecimal totalStock = stockMovementRepository.calculateTotalStock(p.getId());
        ProductResponse r = new ProductResponse();
        r.setId(p.getId());
        r.setSku(p.getSku());
        r.setName(p.getName());
        r.setDescription(p.getDescription());
        r.setUnit(p.getUnit());
        r.setCostPrice(p.getCostPrice());
        r.setMinStock(p.getMinStock());
        r.setCreatedAt(p.getCreatedAt());
        r.setTotalStock(totalStock);
        if (p.getCategory() != null) {
            r.setCategoryId(p.getCategory().getId());
            r.setCategoryName(p.getCategory().getName());
        }
        r.setLowStock(p.getMinStock() != null && totalStock.compareTo(p.getMinStock()) <= 0);
        return r;
    }
}
