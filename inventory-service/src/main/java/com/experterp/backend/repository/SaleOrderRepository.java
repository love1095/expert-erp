package com.experterp.backend.repository;

import com.experterp.backend.entity.SaleOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    // Serialises confirm/fulfil/cancel/return on the same order
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT so FROM SaleOrder so WHERE so.id = :id")
    Optional<SaleOrder> findByIdForUpdate(@Param("id") Long id);

    Page<SaleOrder> findByCustomerId(Long customerId, Pageable pageable);

    // Optional filters: customer, status and text (q = lower-case LIKE pattern, '' = any) on the order number,
    // customer, customer reference or channel order (e.g. Shopify #1004)
    @Query("SELECT so FROM SaleOrder so LEFT JOIN so.customer c WHERE (:customerId IS NULL OR c.id = :customerId) " +
           "AND (:status IS NULL OR so.status = :status) " +
           "AND (:q = '' OR LOWER(COALESCE(so.orderNumber, '')) LIKE :q OR LOWER(COALESCE(so.customerName, '')) LIKE :q " +
           "  OR LOWER(COALESCE(c.name, '')) LIKE :q OR LOWER(COALESCE(so.customerReference, '')) LIKE :q " +
           "  OR LOWER(COALESCE(so.externalReference, '')) LIKE :q)")
    Page<SaleOrder> search(@Param("customerId") Long customerId, @Param("status") SaleOrder.Status status,
                           @Param("q") String q, Pageable pageable);

    Optional<SaleOrder> findBySourceAndExternalId(SaleOrder.Source source, String externalId);

    long countByCustomerId(Long customerId);

    boolean existsByOrderNumber(String orderNumber);

    // [customerId, orderCount] for each of the given customers that has orders
    @Query("SELECT so.customer.id, COUNT(so) FROM SaleOrder so WHERE so.customer.id IN :ids GROUP BY so.customer.id")
    List<Object[]> countByCustomerIds(@Param("ids") Collection<Long> ids);
}
