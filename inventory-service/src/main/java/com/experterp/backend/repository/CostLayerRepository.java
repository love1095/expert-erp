package com.experterp.backend.repository;

import com.experterp.backend.entity.CostLayer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

public interface CostLayerRepository extends JpaRepository<CostLayer, Long> {

    // FIFO: what is left, oldest first
    @Query("SELECT l FROM CostLayer l WHERE l.productId = :productId AND l.warehouseId = :warehouseId AND l.remaining > 0 " +
           "ORDER BY l.receivedAt, l.id")
    List<CostLayer> findOpen(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId);

    // A receipt's layers whose units no supplier bill has priced yet, oldest first
    @Query("SELECT l FROM CostLayer l WHERE l.purchaseOrderLineId = :lineId AND l.billedQuantity < l.quantity ORDER BY l.receivedAt, l.id")
    List<CostLayer> findUnbilled(@Param("lineId") Long purchaseOrderLineId);

    List<CostLayer> findByMovementIdOrderByIdAsc(Long movementId);

    @Query("SELECT l FROM CostLayer l WHERE l.productId = :productId AND l.remaining > 0 ORDER BY l.warehouseId, l.receivedAt, l.id")
    List<CostLayer> findOpenByProduct(@Param("productId") Long productId);

    boolean existsByProductIdIn(Collection<Long> productIds);

    // Stock value per product and warehouse (what is left in the layers): rows of [productId, warehouseId, value]
    @Query("SELECT l.productId, l.warehouseId, SUM(l.remaining * l.unitCost) FROM CostLayer l " +
           "WHERE l.productId IN :productIds AND l.remaining > 0 GROUP BY l.productId, l.warehouseId")
    List<Object[]> valueByProductAndWarehouse(@Param("productIds") Collection<Long> productIds);

    @Query("SELECT COALESCE(SUM(l.remaining * l.unitCost), 0) FROM CostLayer l WHERE l.remaining > 0")
    BigDecimal totalValue();
}
