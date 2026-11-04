package com.experterp.backend.repository;

import com.experterp.backend.entity.PaymentAccount;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAccountRepository extends JpaRepository<PaymentAccount, Long> {
    java.util.List<PaymentAccount> findAllByOrderByCodeAsc();
    java.util.Optional<PaymentAccount> findFirstByDefaultAccountTrueAndActiveTrue();
    java.util.Optional<PaymentAccount> findFirstByKindAndActiveTrueOrderByCodeAsc(PaymentAccount.Kind kind);
}
