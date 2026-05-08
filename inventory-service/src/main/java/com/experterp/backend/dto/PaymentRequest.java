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

    @NotNull
    private Payment.PaymentMethod method;
}
