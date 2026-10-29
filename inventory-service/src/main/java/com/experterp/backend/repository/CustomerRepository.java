package com.experterp.backend.repository;

import com.experterp.backend.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findFirstByNameIgnoreCaseOrderByIdAsc(String name);

    Optional<Customer> findBySourceAndExternalId(Customer.Source source, String externalId);

    // Oldest customer with a contact using this email (ignoring case)
    @Query("SELECT c FROM Customer c JOIN c.contacts cc WHERE LOWER(cc.email) = LOWER(:email) ORDER BY c.id")
    List<Customer> findByContactEmail(@Param("email") String email, Pageable pageable);

    // Matches the customer name or any contact's name, email, phone or mobile
    @Query("SELECT c FROM Customer c WHERE " +
           "LOWER(c.name) LIKE LOWER(CONCAT('%', :q, '%')) OR EXISTS (" +
           "  SELECT 1 FROM CustomerContact cc WHERE cc.customer = c AND (" +
           "    LOWER(COALESCE(cc.name, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "    LOWER(COALESCE(cc.email, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "    COALESCE(cc.phone, '') LIKE CONCAT('%', :q, '%') OR " +
           "    COALESCE(cc.mobilePhone, '') LIKE CONCAT('%', :q, '%')))")
    Page<Customer> search(@Param("q") String q, Pageable pageable);
}
