package com.experterp.backend.repository;

import com.experterp.backend.entity.CompanyDetails;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyDetailsRepository extends JpaRepository<CompanyDetails, Long> {

    // The current organisation's row (the tenant filter applies)
    Optional<CompanyDetails> findFirstByOrderByIdAsc();
}
