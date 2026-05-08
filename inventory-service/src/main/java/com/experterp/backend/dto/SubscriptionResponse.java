package com.experterp.backend.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SubscriptionResponse {

    // Plan details
    private Long subscriptionId;
    private String planTier;
    private String planDisplayName;
    private int maxProducts;
    private int maxUsers;
    private int maxWarehouses;
    private BigDecimal monthlyPrice;

    // Subscription state
    private String status;
    private LocalDateTime startDate;
    private LocalDateTime endDate;

    // Current usage
    private long productCount;
    private long userCount;
    private long warehouseCount;
}
