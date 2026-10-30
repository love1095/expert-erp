package com.experterp.backend.repository;

import com.experterp.backend.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByInvoice_Id(Long invoiceId);

    // Paid with this credit note on invoices
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.creditNoteId = :creditNoteId")
    BigDecimal sumAppliedFromCreditNote(@Param("creditNoteId") Long creditNoteId);
}
