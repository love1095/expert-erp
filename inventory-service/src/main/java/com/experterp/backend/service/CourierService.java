package com.experterp.backend.service;

import com.experterp.backend.entity.Address;
import com.experterp.backend.entity.SaleOrder;
import com.experterp.backend.integration.courier.CourierClient;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.SaleOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase O: booking the packed cartons of an order with a courier — its services and prices, book, the label, tracking,
 * cancel. Shipping the cartons (N3) then uses the booking's carrier, tracking number and tracking link, so they reach
 * the Shopify fulfilment and the customer's e-mail. A cancelled booking frees its cartons to be booked again.
 */
@Service
@RequiredArgsConstructor
public class CourierService {

    private final JdbcTemplate jdbc;
    private final CourierClient client;
    private final SaleOrderRepository orders;
    private final AuditService auditService;

    public record Shipment(Long id, Long saleOrderId, String mode, String carrier, String serviceCode, String serviceName, BigDecimal price,
                           String consignmentId, String trackingNumber, String trackingUrl, int parcels, String status, String trackingStatus,
                           String trackingText, LocalDateTime trackedAt, Long fulfilmentId, LocalDateTime createdAt, String createdBy,
                           List<Integer> cartons) {}

    public record Quote(String mode, List<Integer> cartons, List<CourierClient.ServiceOption> services) {}

    /** The services that can take the order's packed cartons (not booked yet), cheapest first. */
    @Transactional(readOnly = true)
    public Quote services(Long orderId) {
        Request r = request(orderId, null);
        return new Quote(client.mode(), r.numbers(), client.services(r.shipment()));
    }

    @Transactional
    public Shipment book(Long orderId, String serviceCode) {
        if (serviceCode == null || serviceCode.isBlank()) throw new IllegalArgumentException("Choose a courier service");
        Request r = request(orderId, serviceCode);
        CourierClient.ServiceOption option = client.services(r.shipment()).stream().filter(o -> o.serviceCode().equals(serviceCode)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("That service cannot take these cartons"));
        CourierClient.Booking b = client.book(r.shipment());
        Long id = jdbc.queryForObject("""
                INSERT INTO courier_shipments (tenant_id, sale_order_id, mode, carrier, service_code, service_name, price, consignment_id, tracking_number,
                                               tracking_url, label, parcels, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, TenantContext.get(), orderId, client.mode(), option.carrier(),
                option.serviceCode(), option.name(), option.price(), b.consignmentId(), b.trackingNumber(), b.trackingUrl(), b.label(),
                r.packageIds().size(), auditService.currentActorName());
        jdbc.update("UPDATE packages SET courier_shipment_id = ? WHERE id = ANY(?::bigint[])", id, (Object) r.packageIds().toArray(Long[]::new));
        auditService.log("SALE_ORDER", orderId, "COURIER_BOOKED", Map.of("service", option.name(), "tracking", String.valueOf(b.trackingNumber()),
                "price", String.valueOf(option.price())));
        return shipment(id);
    }

    @Transactional(readOnly = true)
    public List<Shipment> shipments(Long orderId) {
        orderOf(orderId);
        return jdbc.queryForList("SELECT id FROM courier_shipments WHERE tenant_id = ? AND sale_order_id = ? ORDER BY id", Long.class, TenantContext.get(), orderId)
                .stream().map(this::shipment).toList();
    }

    @Transactional(readOnly = true)
    public Shipment shipment(Long id) {
        Long t = TenantContext.get();
        List<Integer> cartons = jdbc.queryForList("SELECT number FROM packages WHERE tenant_id = ? AND courier_shipment_id = ? ORDER BY number", Integer.class, t, id);
        return jdbc.query("SELECT * FROM courier_shipments WHERE tenant_id = ? AND id = ?", rs -> {
            if (!rs.next()) throw new IllegalArgumentException("Courier shipment not found");
            return map(rs, cartons);
        }, t, id);
    }

    /** The label as the courier made it (kept with the booking). */
    @Transactional(readOnly = true)
    public DocumentService.Pdf label(Long id) {
        Shipment s = shipment(id);
        byte[] label = jdbc.queryForObject("SELECT label FROM courier_shipments WHERE id = ?", byte[].class, id);
        if (label == null) label = client.label(s.consignmentId());
        return new DocumentService.Pdf("Label-" + (s.trackingNumber() == null ? s.id() : s.trackingNumber()) + ".pdf", label);
    }

    /** Cancels a booking not shipped yet (the courier refuses once collected); its cartons can be booked or shipped again. */
    @Transactional
    public Shipment cancel(Long id) {
        Shipment s = shipment(id);
        if (!"BOOKED".equals(s.status())) throw new IllegalStateException("Only a booking not shipped yet can be cancelled (this one is " + s.status().toLowerCase() + ")");
        client.cancel(s.consignmentId());
        jdbc.update("UPDATE courier_shipments SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = ? WHERE id = ?", auditService.currentActorName(), id);
        jdbc.update("UPDATE packages SET courier_shipment_id = NULL WHERE tenant_id = ? AND courier_shipment_id = ? AND status = 'PACKED'", TenantContext.get(), id);
        auditService.log("SALE_ORDER", s.saleOrderId(), "COURIER_CANCELLED", Map.of("tracking", String.valueOf(s.trackingNumber())));
        return shipment(id);
    }

    /** Asks the courier where the parcel is; delivered marks the shipment delivered. */
    @Transactional
    public Shipment track(Long id) {
        Shipment s = shipment(id);
        if ("CANCELLED".equals(s.status())) throw new IllegalStateException("This booking was cancelled");
        CourierClient.Tracking tr = client.track(s.consignmentId());
        jdbc.update("""
                UPDATE courier_shipments SET tracking_status = ?, tracking_text = ?, tracked_at = now(),
                       status = CASE WHEN ? = 'DELIVERED' THEN 'DELIVERED' ELSE status END WHERE id = ?""",
                tr.status(), cut(tr.description(), 255), tr.status(), id);
        return shipment(id);
    }

    // ---- shared ----

    private record Request(CourierClient.ShipmentRequest shipment, List<Long> packageIds, List<Integer> numbers) {}

    private Request request(Long orderId, String serviceCode) {
        SaleOrder so = orderOf(orderId);
        List<Long> ids = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        List<CourierClient.Parcel> parcels = new ArrayList<>();
        jdbc.query("""
                SELECT id, number, weight_kg, length_cm, width_cm, height_cm FROM packages
                 WHERE tenant_id = ? AND sale_order_id = ? AND status = 'PACKED' AND courier_shipment_id IS NULL ORDER BY number""", rs -> {
            BigDecimal w = rs.getBigDecimal("weight_kg");
            if (w == null || w.signum() <= 0) throw new IllegalArgumentException("Give carton " + rs.getInt("number") + " a weight first: the courier prices by weight");
            ids.add(rs.getLong("id"));
            numbers.add(rs.getInt("number"));
            parcels.add(new CourierClient.Parcel(w, rs.getBigDecimal("length_cm"), rs.getBigDecimal("width_cm"), rs.getBigDecimal("height_cm")));
        }, TenantContext.get(), orderId);
        if (ids.isEmpty()) throw new IllegalStateException("No packed cartons to book: pack the order first (cartons already booked are on their booking)");
        Address a = so.getShippingAddress();
        String name = so.getShipToContact() != null ? so.getShipToContact() : so.getCustomerName();
        CourierClient.Address to = new CourierClient.Address(name, so.getShipToCompany(), a == null ? List.of() : a.lines(),
                a == null ? null : a.getPostcode(), a == null || a.getCountry() == null ? "GB" : a.getCountry(), null, null);
        return new Request(new CourierClient.ShipmentRequest(so.getOrderNumber(), to, parcels, null, serviceCode), ids, numbers);
    }

    private SaleOrder orderOf(Long orderId) {
        return orders.findById(orderId).orElseThrow(() -> new IllegalArgumentException("Sale order not found"));
    }

    private static Shipment map(ResultSet rs, List<Integer> cartons) throws SQLException {
        return new Shipment(rs.getLong("id"), rs.getLong("sale_order_id"), rs.getString("mode"), rs.getString("carrier"), rs.getString("service_code"),
                rs.getString("service_name"), rs.getBigDecimal("price"), rs.getString("consignment_id"), rs.getString("tracking_number"),
                rs.getString("tracking_url"), rs.getInt("parcels"), rs.getString("status"), rs.getString("tracking_status"), rs.getString("tracking_text"),
                ts(rs.getTimestamp("tracked_at")), rs.getObject("fulfilment_id") == null ? null : rs.getLong("fulfilment_id"),
                ts(rs.getTimestamp("created_at")), rs.getString("created_by"), cartons);
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
