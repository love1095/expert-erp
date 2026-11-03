package com.experterp.backend.repository;

import com.experterp.backend.entity.CustomerPrice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface CustomerPriceRepository extends JpaRepository<CustomerPrice, Long> {

    @Query("SELECT p FROM CustomerPrice p JOIN FETCH p.product WHERE p.customerId = :customerId ORDER BY p.product.sku, p.validFrom DESC")
    List<CustomerPrice> forCustomer(@Param("customerId") Long customerId);

    List<CustomerPrice> findByCustomerIdAndProductId(Long customerId, Long productId);

    // Contract prices of these products valid on the day
    @Query("""
            SELECT p FROM CustomerPrice p WHERE p.customerId = :customerId AND p.product.id IN :productIds
              AND p.validFrom <= :day AND (p.validTo IS NULL OR p.validTo >= :day)""")
    List<CustomerPrice> validOn(@Param("customerId") Long customerId, @Param("productIds") Collection<Long> productIds,
                                @Param("day") LocalDate day);
}
