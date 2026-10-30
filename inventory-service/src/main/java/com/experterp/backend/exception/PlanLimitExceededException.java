package com.experterp.backend.exception;

import lombok.Getter;

@Getter
public class PlanLimitExceededException extends RuntimeException {

    private final String resource;
    private final int limit;
    private final String planTier;

    public PlanLimitExceededException(String resource, int limit, String planTier) {
        this(resource, limit, planTier, "Plan limit reached: " + resource + " (max " + limit + " on " + planTier + " plan)");
    }

    public PlanLimitExceededException(String resource, int limit, String planTier, String message) {
        super(message);
        this.resource = resource;
        this.limit = limit;
        this.planTier = planTier;
    }
}
