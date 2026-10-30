package com.experterp.backend.repository;

import com.experterp.backend.entity.PurchaseReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseReceiptRepository extends JpaRepository<PurchaseReceipt, Long> {

    List<PurchaseReceipt> findByPurchaseOrderIdOrderByCreatedAtAscIdAsc(Long purchaseOrderId);

    Optional<PurchaseReceipt> findByIdempotencyKey(String idempotencyKey);
}
