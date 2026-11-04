package com.experterp.backend.repository;

import com.experterp.backend.entity.SupplierCredit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierCreditRepository extends JpaRepository<SupplierCredit, Long> {
    java.util.List<SupplierCredit> findBySupplierIdOrderByPaidOnAscIdAsc(Long supplierId);
    boolean existsByPaymentAccountId(Long paymentAccountId);
}
