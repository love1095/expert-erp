package com.experterp.backend.repository;

import com.experterp.backend.entity.CostConsumption;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CostConsumptionRepository extends JpaRepository<CostConsumption, Long> {

    List<CostConsumption> findByLayerIdOrderByIdAsc(Long layerId);

    List<CostConsumption> findByMovementIdOrderByIdAsc(Long movementId);
}
