package com.experterp.backend.exception;

import lombok.Getter;

@Getter
public class PlanLimitExceededException extends RuntimeException {

    private final String resource;
    private final int limit;
    private final String planTier;

    public PlanLimitExceededException(String resource, int limit, String planTier) {
        super("Plan limit reached: " + resource + " (max " + limit + " on " + planTier + " plan)");
        this.resource = resource;
        this.limit = limit;
        this.planTier = planTier;
    }
}
