package com.experterp.backend.repository;

import com.experterp.backend.entity.VoidedPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoidedPaymentRepository extends JpaRepository<VoidedPayment, Long> {
    java.util.List<VoidedPayment> findByInvoiceIdOrderByVoidedAtAsc(Long invoiceId);
    boolean existsByPaymentAccountId(Long paymentAccountId);
}
