package com.experterp.backend.dto;

import com.experterp.backend.entity.Payment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PaymentRequest {

    @NotNull @Positive
    private BigDecimal amount;

    // How it was paid; may be left out when a payment account is given (its kind says it) or there is a default account
    private Payment.PaymentMethod method;

    // Phase H1: the payment account it went to (e.g. 650 Current Account - RBS); empty = the default account
    private Long accountId;

    // The day it was paid; empty = today. Not in the future
    private java.time.LocalDate paidOn;

    @jakarta.validation.constraints.Size(max = 100)
    private String reference;
}
