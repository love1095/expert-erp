package com.experterp.backend.repository;

import com.experterp.backend.entity.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {
    Optional<Warehouse> findByName(String name);

    Optional<Warehouse> findFirstByOrderByIdAsc();
    boolean existsByName(String name);
}
