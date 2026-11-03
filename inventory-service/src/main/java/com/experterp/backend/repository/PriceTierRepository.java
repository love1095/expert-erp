package com.experterp.backend.repository;

import com.experterp.backend.entity.PriceTier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceTierRepository extends JpaRepository<PriceTier, Long> {

    List<PriceTier> findAllByOrderByNumberAsc();
}
