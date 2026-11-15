package com.experterp.backend.repository;

import com.experterp.backend.entity.SaleReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SaleReturnRepository extends JpaRepository<SaleReturn, Long> {
    Optional<SaleReturn> findByIdempotencyKey(String idempotencyKey);
    List<SaleReturn> findBySaleOrderIdOrderByCreatedAtAsc(Long saleOrderId);

    /**
     * Per line of the order (line id, quantity): units already back in stock through a return that no credit note
     * has covered yet. A credit note with "restock" takes these first instead of putting goods back again.
     */
    @Query(value = """
            SELECT l.id, GREATEST(COALESCE((SELECT SUM(rl.quantity) FROM sale_return_lines rl
                                             WHERE rl.sale_order_line_id = l.id AND rl.restock), 0) - l.restock_credited_quantity, 0)
              FROM sale_order_lines l WHERE l.sale_order_id = :orderId""", nativeQuery = true)
    List<Object[]> backInStockNotCredited(@Param("orderId") Long orderId);
}
