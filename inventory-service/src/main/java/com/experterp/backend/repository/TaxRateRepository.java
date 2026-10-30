package com.experterp.backend.repository;

import com.experterp.backend.entity.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {

    List<TaxRate> findByUsageOrderByRateDescNameAsc(TaxRate.Usage usage);

    Optional<TaxRate> findFirstByUsageAndNameIgnoreCaseAndActiveTrue(TaxRate.Usage usage, String name);
}
