package com.experterp.backend.repository;

import com.experterp.backend.entity.CostAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface CostAdjustmentRepository extends JpaRepository<CostAdjustment, Long> {

    // The rows that matched a bill item to a layer (billed_quantity set)
    @Query("SELECT a FROM CostAdjustment a WHERE a.invoiceItemId = :itemId AND a.billedQuantity IS NOT NULL ORDER BY a.id")
    List<CostAdjustment> findMatches(@Param("itemId") Long invoiceItemId);

    // Total cost change caused by some bill items (stock and COGS together)
    @Query("SELECT COALESCE(SUM(a.amount), 0) FROM CostAdjustment a WHERE a.invoiceItemId IN :itemIds")
    BigDecimal totalForItems(@Param("itemIds") Collection<Long> invoiceItemIds);
}
