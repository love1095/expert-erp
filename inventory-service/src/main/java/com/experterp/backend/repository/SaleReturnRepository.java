package com.experterp.backend.repository;

import com.experterp.backend.entity.SaleReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SaleReturnRepository extends JpaRepository<SaleReturn, Long> {
    Optional<SaleReturn> findByIdempotencyKey(String idempotencyKey);
    List<SaleReturn> findBySaleOrderIdOrderByCreatedAtAsc(Long saleOrderId);
}
