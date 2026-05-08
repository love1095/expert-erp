package com.experterp.backend.repository;

import com.experterp.backend.entity.SaleOrder;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {
}
