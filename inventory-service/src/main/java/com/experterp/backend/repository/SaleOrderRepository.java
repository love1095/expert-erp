package com.experterp.backend.repository;

import com.experterp.backend.entity.SaleOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    // Serialises confirm/fulfil/cancel/return on the same order
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT so FROM SaleOrder so WHERE so.id = :id")
    Optional<SaleOrder> findByIdForUpdate(@Param("id") Long id);
}
