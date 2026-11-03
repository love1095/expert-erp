package com.experterp.backend.repository;

import com.experterp.backend.entity.ShopifyRefund;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ShopifyRefundRepository extends JpaRepository<ShopifyRefund, Long> {

    boolean existsByRefundId(String refundId);

    List<ShopifyRefund> findBySaleOrderIdOrderByRefundedAtAscIdAsc(Long saleOrderId);

    List<ShopifyRefund> findBySaleOrderIdIn(Collection<Long> saleOrderIds);

    long countByStatus(ShopifyRefund.Status status);

    List<ShopifyRefund> findByStatusOrderByRefundedAtAsc(ShopifyRefund.Status status);
}
