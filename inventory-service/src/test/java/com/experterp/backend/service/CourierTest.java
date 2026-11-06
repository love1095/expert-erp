package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.integration.courier.CourierClient;
import com.experterp.backend.integration.courier.FakeCourierClient;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase O: booking packed cartons with the (fake) courier, the label, cancelling, shipping with its tracking, e-mail, tracking. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class CourierTest {

    @Autowired CourierService couriers;
    @Autowired FakeCourierClient fake;
    @Autowired PickPackService pickPack;
    @Autowired SaleOrderService saleOrderService;
    @Autowired StockMovementService movements;
    @Autowired MailService mail;
    @Autowired FulfilmentRepository fulfilments;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Warehouse main;
    Product boots;
    Customer abbey;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Courier " + id);
        t.setSlug("courier-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        main = new Warehouse();
        main.setName("Main " + id);
        main = warehouseRepository.save(main);
        boots = new Product();
        boots.setSku("BOOTS-" + id);
        boots.setName("Boots");
        boots.setUnit("Each");
        boots = productRepository.save(boots);
        movements.createMovement(boots, main, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "IN", null, null, new BigDecimal("12.00"));
        abbey = new Customer();
        abbey.setName("Abbey " + id);
        CustomerContact cc = new CustomerContact();
        cc.setCustomer(abbey);
        cc.setName("Stores");
        cc.setEmail("stores@abbey.example");
        cc.setDefaultContact(true);
        abbey.getContacts().add(cc);
        abbey = customerRepository.save(abbey);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void cartonsAreBookedCancelledBookedAgainAndShippedWithTheCouriersTrackingAndLabel() {
        SaleOrderResponse so = packed(new BigDecimal("2.5"));

        // Services for one 2.5 kg carton, cheapest first; a carton too heavy for Royal Mail and Evri leaves them out
        CourierService.Quote q = couriers.services(so.getId());
        assertThat(q.mode()).isEqualTo("fake");
        assertThat(q.cartons()).containsExactly(1);
        assertThat(q.services()).extracting(CourierClient.ServiceOption::price).isSortedAccordingTo(BigDecimal::compareTo);
        assertThat(q.services()).extracting(CourierClient.ServiceOption::carrier).contains("Royal Mail", "DPD", "Parcelforce", "Evri");

        // Booked with DPD: tracking, a real label PDF, the carton on the booking; nothing more to book
        CourierService.Shipment dpd = couriers.book(so.getId(), "DPD-NEXT-DAY");
        assertThat(dpd.status()).isEqualTo("BOOKED");
        assertThat(dpd.trackingNumber()).startsWith("1550");
        assertThat(dpd.price()).isEqualByComparingTo("7.58");                   // £6.95 + 2.5 kg × £0.25
        assertThat(dpd.cartons()).containsExactly(1);
        assertThat(new String(couriers.label(dpd.id()).bytes(), 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThatThrownBy(() -> couriers.services(so.getId())).hasMessageContaining("No packed cartons to book");

        // Cancelled before collection: the carton is free again and booked with Royal Mail instead
        assertThat(couriers.cancel(dpd.id()).status()).isEqualTo("CANCELLED");
        assertThat(couriers.shipment(dpd.id()).cartons()).isEmpty();
        CourierService.Shipment rm = couriers.book(so.getId(), "RM-TRACKED-48");
        assertThat(rm.trackingNumber()).matches("FK\\d{9}GB");

        // Shipping the cartons uses the booking: carrier, tracking number and link on the shipment (so Shopify gets them)
        PickPackService.OrderWarehouse w = pickPack.ship(so.getId(), null, null);
        assertThat(w.stage()).isEqualTo("SHIPPED");
        Fulfilment f = fulfilments.findBySaleOrderIdOrderByCreatedAtAsc(so.getId()).get(0);
        assertThat(f.getTrackingCompany()).isEqualTo("Royal Mail Tracked 48");
        assertThat(f.getTrackingNumber()).isEqualTo(rm.trackingNumber());
        assertThat(f.getTrackingUrl()).endsWith(rm.trackingNumber());
        CourierService.Shipment shipped = couriers.shipment(rm.id());
        assertThat(shipped.status()).isEqualTo("SHIPPED");
        assertThat(shipped.fulfilmentId()).isEqualTo(f.getId());
        assertThatThrownBy(() -> couriers.cancel(rm.id())).hasMessageContaining("not shipped yet");

        // The tracking e-mailed to the customer, in the order's e-mails
        mail.updateSettings(new MailService.SettingsRequest("Expert Safety", "despatch@expertsafety.example", null, null, null, null, null, true));
        MailService.Sent sent = mail.send(new MailService.Request("TRACKING", f.getId()));
        assertThat(sent.to()).isEqualTo("stores@abbey.example");
        assertThat(sent.docKey()).isEqualTo("SO:" + so.getId());
        assertThat(sent.subject()).contains(so.getOrderNumber());
        assertThat(sent.body()).contains(rm.trackingNumber()).contains("Royal Mail Tracked 48").contains("https://tracking.example.test/");
        assertThat(mail.outbox("SO:" + so.getId(), 10, null)).extracting(MailService.Sent::docType).contains(MailService.DocType.TRACKING);

        // Tracking moves on until delivered
        fake.advance(rm.consignmentId());
        assertThat(couriers.track(rm.id()).trackingStatus()).isEqualTo("COLLECTED");
        fake.advance(rm.consignmentId());
        fake.advance(rm.consignmentId());
        CourierService.Shipment delivered = couriers.track(rm.id());
        assertThat(delivered.status()).isEqualTo("DELIVERED");
        assertThat(delivered.trackingText()).isEqualTo("Delivered");
    }

    @Test
    void heavyOrUnweighedCartonsCourierErrorsAndOtherOrganisations() {
        SaleOrderResponse unweighed = packed(null);
        assertThatThrownBy(() -> couriers.services(unweighed.getId())).hasMessageContaining("Give carton 1 a weight");

        SaleOrderResponse heavy = packed(new BigDecimal("25"));
        assertThat(couriers.services(heavy.getId()).services()).extracting(CourierClient.ServiceOption::carrier)
                .containsOnly("DPD", "Parcelforce");
        assertThatThrownBy(() -> couriers.book(heavy.getId(), "EVRI-STANDARD")).hasMessageContaining("cannot take");

        // The courier down: its message shown, nothing booked
        fake.failNext("DPD is not answering");
        assertThatThrownBy(() -> couriers.book(heavy.getId(), "DPD-NEXT-DAY")).hasMessageContaining("DPD is not answering");
        assertThat(couriers.shipments(heavy.getId())).isEmpty();
        CourierService.Shipment s = couriers.book(heavy.getId(), "DPD-NEXT-DAY");

        // Collected: cannot be cancelled any more
        fake.advance(s.consignmentId());
        assertThatThrownBy(() -> couriers.cancel(s.id())).hasMessageContaining("Already collected");
        assertThat(couriers.shipment(s.id()).status()).isEqualTo("BOOKED");

        // Another organisation sees nothing
        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-courier-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThatThrownBy(() -> couriers.shipment(s.id())).hasMessageContaining("not found");
        assertThatThrownBy(() -> couriers.shipments(heavy.getId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> couriers.label(s.id())).hasMessageContaining("not found");
    }

    /** An order for 2 boots to a delivery address, picked and packed into one carton of that weight. */
    private SaleOrderResponse packed(BigDecimal weight) {
        SaleOrderRequest r = new SaleOrderRequest();
        r.setCustomerId(abbey.getId());
        r.setCustomerName(abbey.getName());
        r.setWarehouseId(main.getId());
        r.setShippingAddress(new Address("Unit 4, Canal Street", null, "Manchester", null, "M1 3HE", null));
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(boots.getId());
        l.setQuantity(new BigDecimal("2"));
        l.setUnitPrice(new BigDecimal("40"));
        r.setLines(List.of(l));
        SaleOrderResponse so = saleOrderService.confirm(saleOrderService.create(r).getId());
        pickPack.confirm(pickPack.create(List.of(so.getId())).id(), null);
        Long line = pickPack.order(so.getId()).lines().get(0).saleOrderLineId();
        pickPack.pack(so.getId(), List.of(new PickPackService.Carton(weight, null, null, null, List.of(new PickPackService.PackLine(line, new BigDecimal("2"))))));
        return so;
    }
}
