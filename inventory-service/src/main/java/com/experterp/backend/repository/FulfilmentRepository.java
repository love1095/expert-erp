package com.experterp.backend.repository;

import com.experterp.backend.entity.Fulfilment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FulfilmentRepository extends JpaRepository<Fulfilment, Long> {
    Optional<Fulfilment> findByIdempotencyKey(String idempotencyKey);
    List<Fulfilment> findBySaleOrderIdOrderByCreatedAtAsc(Long saleOrderId);
}
