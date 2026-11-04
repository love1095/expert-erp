package com.experterp.backend.repository;

import com.experterp.backend.entity.CustomerCredit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerCreditRepository extends JpaRepository<CustomerCredit, Long> {
    java.util.List<CustomerCredit> findByCustomerIdOrderByReceivedOnAscIdAsc(Long customerId);
    boolean existsByPaymentAccountId(Long paymentAccountId);
}
