package com.experterp.backend.integration.courier;

import com.experterp.backend.service.PdfRenderer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase O: couriers that book nothing. Four UK carriers with weight limits and prices by weight, a test label (a real
 * 100 × 150 mm PDF marked TEST), tracking numbers in each carrier's style, and tracking that moves on when a test
 * calls {@link #advance}. Kept in memory: a restart forgets the bookings (labels are kept by the ERP).
 */
@Component
@ConditionalOnProperty(name = "app.courier.mode", havingValue = "fake", matchIfMissing = true)
public class FakeCourierClient implements CourierClient {

    private record Carrier(String name, String code, String service, BigDecimal base, BigDecimal perKg, BigDecimal maxKg, int days) {}

    private static final List<Carrier> CARRIERS = List.of(
            new Carrier("Royal Mail", "RM", "Tracked 48", new BigDecimal("3.60"), new BigDecimal("0.30"), new BigDecimal("20"), 2),
            new Carrier("Royal Mail", "RM", "Tracked 24", new BigDecimal("4.70"), new BigDecimal("0.30"), new BigDecimal("20"), 1),
            new Carrier("DPD", "DPD", "Next Day", new BigDecimal("6.95"), new BigDecimal("0.25"), new BigDecimal("30"), 1),
            new Carrier("Parcelforce", "PF", "Express 48", new BigDecimal("9.50"), new BigDecimal("0.20"), new BigDecimal("30"), 2),
            new Carrier("Evri", "EVRI", "Standard", new BigDecimal("2.95"), new BigDecimal("0.35"), new BigDecimal("15"), 3));

    private static final List<String> STEPS = List.of("BOOKED", "COLLECTED", "IN_TRANSIT", "DELIVERED");

    private record Booked(String carrier, String tracking, byte[] label, int step, boolean cancelled) {}

    private final Map<String, Booked> booked = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis() % 1_000_000);
    private final PdfRenderer renderer;
    private volatile String failNext;

    public FakeCourierClient(PdfRenderer renderer) {
        this.renderer = renderer;
    }

    @Override
    public String mode() { return "fake"; }

    @Override
    public List<ServiceOption> services(ShipmentRequest r) {
        fail();
        BigDecimal heaviest = r.parcels().stream().map(p -> p.weightKg() == null ? BigDecimal.ZERO : p.weightKg()).max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
        BigDecimal total = r.parcels().stream().map(p -> p.weightKg() == null ? BigDecimal.ZERO : p.weightKg()).reduce(BigDecimal.ZERO, BigDecimal::add);
        return CARRIERS.stream().filter(c -> heaviest.compareTo(c.maxKg()) <= 0)
                .map(c -> new ServiceOption(c.name(), code(c), c.name() + " " + c.service(),
                        c.base().multiply(BigDecimal.valueOf(r.parcels().size())).add(c.perKg().multiply(total)).setScale(2, RoundingMode.HALF_UP), c.days()))
                .sorted(Comparator.comparing(ServiceOption::price)).toList();
    }

    @Override
    public Booking book(ShipmentRequest r) {
        fail();
        if (r.to() == null || r.to().lines() == null || r.to().lines().isEmpty() || blank(r.to().postcode())) {
            throw new CourierException("The courier needs a delivery address with a postcode");
        }
        ServiceOption s = services(r).stream().filter(o -> o.serviceCode().equals(r.serviceCode())).findFirst()
                .orElseThrow(() -> new CourierException("That service cannot take these parcels"));
        Carrier c = CARRIERS.stream().filter(x -> code(x).equals(s.serviceCode())).findFirst().orElseThrow();
        long n = seq.incrementAndGet();
        String tracking = switch (c.code()) {
            case "RM" -> "FK%09dGB".formatted(n);
            case "DPD" -> "1550%010d".formatted(n);
            case "PF" -> "PF%08dTEST".formatted(n);
            default -> "H00TEST%09d".formatted(n);
        };
        String consignment = "FAKE-" + n;
        Map<String, Object> label = new LinkedHashMap<>();
        label.put("carrier", s.name());
        label.put("tracking", tracking);
        label.put("reference", r.reference());
        label.put("parcels", r.parcels().size());
        List<String> to = new ArrayList<>();
        if (!blank(r.to().name())) to.add(r.to().name());
        if (!blank(r.to().company()) && !r.to().company().equals(r.to().name())) to.add(r.to().company());
        to.addAll(r.to().lines());
        if (to.stream().noneMatch(x -> x.equalsIgnoreCase(r.to().postcode()))) to.add(r.to().postcode());
        label.put("to", to);
        byte[] pdf = renderer.render("label", label, tracking);
        booked.put(consignment, new Booked(c.name(), tracking, pdf, 0, false));
        return new Booking(consignment, tracking, "https://tracking.example.test/" + tracking, pdf);
    }

    @Override
    public byte[] label(String consignmentId) {
        return get(consignmentId).label();
    }

    @Override
    public Tracking track(String consignmentId) {
        fail();
        Booked b = get(consignmentId);
        String status = b.cancelled() ? "CANCELLED" : STEPS.get(b.step());
        String text = switch (status) {
            case "BOOKED" -> "Waiting for collection";
            case "COLLECTED" -> "Collected by " + b.carrier();
            case "IN_TRANSIT" -> "On its way";
            case "DELIVERED" -> "Delivered";
            default -> "Cancelled";
        };
        return new Tracking(status, text, LocalDateTime.now());
    }

    @Override
    public void cancel(String consignmentId) {
        fail();
        Booked b = get(consignmentId);
        if (b.step() > 0) throw new CourierException("Already collected by " + b.carrier() + ": it cannot be cancelled");
        booked.put(consignmentId, new Booked(b.carrier(), b.tracking(), b.label(), b.step(), true));
    }

    // ---- for tests ----

    /** Moves the parcel one step on: collected, in transit, delivered. */
    public void advance(String consignmentId) {
        Booked b = get(consignmentId);
        booked.put(consignmentId, new Booked(b.carrier(), b.tracking(), b.label(), Math.min(b.step() + 1, STEPS.size() - 1), b.cancelled()));
    }

    /** The next call fails with this message (the courier down or refusing). */
    public void failNext(String message) {
        this.failNext = message;
    }

    private void fail() {
        String m = failNext;
        if (m != null) {
            failNext = null;
            throw new CourierException(m);
        }
    }

    private Booked get(String consignmentId) {
        Booked b = booked.get(consignmentId);
        if (b == null) throw new CourierException("The courier does not know shipment " + consignmentId);
        return b;
    }

    private static String code(Carrier c) {
        return c.code() + "-" + c.service().toUpperCase(Locale.ROOT).replace(' ', '-');
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
