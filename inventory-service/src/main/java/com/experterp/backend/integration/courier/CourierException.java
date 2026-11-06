package com.experterp.backend.integration.courier;

/** A courier refused or could not be reached; the message is the courier's, fit to show. */
public class CourierException extends IllegalStateException {
    public CourierException(String message) {
        super(message);
    }
}
