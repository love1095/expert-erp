package com.experterp.backend.integration.courier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase O: the live couriers are not built yet: the owner has to name the couriers and accounts in use (Royal Mail
 * Click &amp; Drop, DPD, Parcelforce, Evri, or one shipping hub). Until then live mode refuses clearly.
 */
@Component
@ConditionalOnProperty(name = "app.courier.mode", havingValue = "live")
public class LiveCourierClient implements CourierClient {

    private static final String NOT_YET = "No live courier is set up yet: book with the courier's own site and type the tracking number";

    @Override public String mode() { return "live"; }
    @Override public List<ServiceOption> services(ShipmentRequest request) { throw new CourierException(NOT_YET); }
    @Override public Booking book(ShipmentRequest request) { throw new CourierException(NOT_YET); }
    @Override public byte[] label(String consignmentId) { throw new CourierException(NOT_YET); }
    @Override public Tracking track(String consignmentId) { throw new CourierException(NOT_YET); }
    @Override public void cancel(String consignmentId) { throw new CourierException(NOT_YET); }
}
