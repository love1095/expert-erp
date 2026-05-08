package com.experterp.backend.repository;

import com.experterp.backend.entity.Plan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {
    Optional<Plan> findByTier(Plan.PlanTier tier);
    boolean existsByTier(Plan.PlanTier tier);
}
