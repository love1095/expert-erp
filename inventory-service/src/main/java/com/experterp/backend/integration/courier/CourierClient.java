package com.experterp.backend.integration.courier;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase O: a courier (or a shipping hub): its services and prices for some parcels, booking a shipment (a tracking
 * number and a label), the label again, tracking and cancelling. {@code app.courier.mode=fake} (the default) books
 * against {@link FakeCourierClient}; live clients come once the owner names the couriers and accounts.
 */
public interface CourierClient {

    record Parcel(BigDecimal weightKg, BigDecimal lengthCm, BigDecimal widthCm, BigDecimal heightCm) {}

    record Address(String name, String company, List<String> lines, String postcode, String country, String email, String phone) {}

    record ServiceOption(String carrier, String serviceCode, String name, BigDecimal price, int days) {}

    record ShipmentRequest(String reference, Address to, List<Parcel> parcels, String carrier, String serviceCode) {}

    record Booking(String consignmentId, String trackingNumber, String trackingUrl, byte[] label) {}

    /** status: BOOKED, COLLECTED, IN_TRANSIT, DELIVERED, CANCELLED */
    record Tracking(String status, String description, LocalDateTime at) {}

    String mode();

    List<ServiceOption> services(ShipmentRequest request);

    Booking book(ShipmentRequest request);

    byte[] label(String consignmentId);

    Tracking track(String consignmentId);

    void cancel(String consignmentId);
}
