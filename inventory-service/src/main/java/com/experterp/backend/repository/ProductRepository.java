package com.experterp.backend.repository;

import com.experterp.backend.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findBySku(String sku);
    boolean existsBySku(String sku);
    Page<Product> findByCategory_Id(Long categoryId, Pageable pageable);

    // Filters are optional (null = any); q is a lower-case LIKE pattern on SKU or name (trigram-indexed, V10)
    @EntityGraph(attributePaths = {"category", "defaultSupplier"})
    @Query("SELECT p FROM Product p LEFT JOIN p.category c " +
           "WHERE (:categoryId IS NULL OR c.id = :categoryId) " +
           "AND (:status IS NULL OR p.status = :status) " +
           "AND (:q IS NULL OR LOWER(p.sku) LIKE :q OR LOWER(p.name) LIKE :q)")
    Page<Product> search(@Param("categoryId") Long categoryId, @Param("status") Product.Status status,
                         @Param("q") String q, Pageable pageable);

    List<Product> findBySkuIn(Collection<String> skus);

    // [id, sku, name, unit, categoryId] for dropdowns
    @Query("SELECT p.id, p.sku, p.name, p.unit, c.id FROM Product p LEFT JOIN p.category c " +
           "WHERE (:status IS NULL OR p.status = :status) ORDER BY p.sku")
    List<Object[]> findOptions(@Param("status") Product.Status status);

    @Query("SELECT p.sku FROM Product p")
    Set<String> findAllSkus();

    // [id, sku] of the current tenant's active products
    @Query("SELECT p.id, p.sku FROM Product p WHERE p.status = com.experterp.backend.entity.Product.Status.ACTIVE")
    List<Object[]> findActiveIdsAndSkus();

    // Native, so the tenant is filtered by hand
    @Modifying
    @Query(value = "UPDATE products SET status = 'DEPRECATED', updated_at = :now WHERE tenant_id = :tenantId AND id IN (:ids)",
           nativeQuery = true)
    int deprecate(@Param("tenantId") Long tenantId, @Param("ids") Collection<Long> ids, @Param("now") LocalDateTime now);

    @EntityGraph(attributePaths = {"category", "defaultSupplier"})
    @Query("SELECT p FROM Product p WHERE (:status IS NULL OR p.status = :status)")
    Slice<Product> findForExport(@Param("status") Product.Status status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") Long id);
}
