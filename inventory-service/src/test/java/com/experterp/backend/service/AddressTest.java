package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.dto.OrderInvoiceRequest;
import com.experterp.backend.dto.SaleOrderRequest;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.CustomerAddress.Type;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase G4 (spec §5): customers' address book like Cin7's; billing and shipping addresses copied onto orders. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AddressTest {

    @Autowired CustomerService customerService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired DocumentService documentService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Product helmet;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Address " + id);
        t.setSlug("address-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Main");
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELM-" + id.substring(0, 6));
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "T", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theAddressBookKeepsOneDefaultPerTypeAndIsLeftAloneWhenNotSent() {
        CustomerRequest req = customer("Loudfoot Builders",
                address(Type.BILLING, true, "Barlow Fold", "Bury"),
                address(Type.BILLING, true, "Old Office", "Bury"),
                address(Type.SHIPPING, false, "Site 4, Canal Street", "Manchester"),
                address(Type.SHIPPING, false, "Yard 2", "Bolton"),
                address(Type.SHIPPING, false, " ", null));
        CustomerResponse c = customerService.create(req);

        assertThat(c.getAddresses()).hasSize(4);
        assertThat(c.getAddresses()).filteredOn(CustomerResponse.Address::isDefaultForType)
                .extracting(CustomerResponse.Address::getLine1).containsExactlyInAnyOrder("Barlow Fold", "Site 4, Canal Street");
        assertThat(c.getAddress()).isEqualTo("Barlow Fold, Bury BL9 9SX, United Kingdom");

        // Saving the customer without addresses (e.g. the CSV import) keeps them
        req.setAddresses(null);
        req.setPaymentTerm("30 Day EOM");
        assertThat(customerService.update(c.getId(), req).getAddresses()).hasSize(4);
    }

    @Test
    void anOrderCopiesTheDefaultsCanBeChangedAndKeepsItsAddressesWhenTheCustomerMoves() throws IOException {
        CustomerRequest req = customer("Abbey Attachments", address(Type.BILLING, true, "Barlow Fold", "Bury"),
                address(Type.SHIPPING, true, "Site 4, Canal Street", "Manchester"));
        Long customerId = customerService.create(req).getId();

        SaleOrderResponse so = saleOrderService.create(order(customerId, null));
        assertThat(so.getBillingAddress().getLine1()).isEqualTo("Barlow Fold");
        assertThat(so.getShippingAddress().getLine1()).isEqualTo("Site 4, Canal Street");

        // A one-off delivery address on the order, with who receives it
        SaleOrderRequest oneOff = order(customerId, new Address("Gate 3, Trafford Park", null, "Manchester", null, "M17 1AA", null));
        oneOff.setShipToCompany("Trafford Builders");
        oneOff.setShipToContact("Site office");
        SaleOrderResponse changed = saleOrderService.update(so.getId(), oneOff);
        assertThat(changed.getShippingAddress().getLine1()).isEqualTo("Gate 3, Trafford Park");
        assertThat(changed.getBillingAddress().getLine1()).isEqualTo("Barlow Fold");

        // The customer moves: the order keeps the addresses it was made with
        req.setAddresses(List.of(address(Type.BILLING, true, "New House", "Rochdale")));
        customerService.update(customerId, req);
        assertThat(saleOrderService.findById(so.getId()).getBillingAddress().getLine1()).isEqualTo("Barlow Fold");
        assertThat(saleOrderService.create(order(customerId, null)).getShippingAddress().getLine1())
                .as("no shipping address left: goods go to the billing address").isEqualTo("New House");

        // Printed: billed at Barlow Fold, delivered to Trafford Park, on the order and on its invoice
        assertThat(text(documentService.saleOrder(so.getId()).bytes()))
                .contains("barlow fold", "deliver to", "trafford builders", "attn: site office", "gate 3, trafford park", "manchester m17 1aa");
        saleOrderService.confirm(so.getId());
        saleOrderService.fulfill(so.getId(), null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        Long invoiceId = invoiceService.invoiceOrder(so.getId(), issue).getId();
        assertThat(text(documentService.invoice(invoiceId).bytes()))
                .contains("invoice to", "barlow fold", "deliver to", "gate 3, trafford park")
                .doesNotContain("new house");
    }

    // ---- helpers ----

    private static CustomerRequest customer(String name, CustomerRequest.Address... addresses) {
        CustomerRequest req = new CustomerRequest();
        req.setName(name);
        req.setAddresses(List.of(addresses));
        return req;
    }

    private static CustomerRequest.Address address(Type type, boolean isDefault, String line1, String city) {
        CustomerRequest.Address a = new CustomerRequest.Address();
        a.setType(type);
        a.setDefaultForType(isDefault);
        a.setLine1(line1);
        a.setCity(city);
        if ("Barlow Fold".equals(line1)) {
            a.setPostcode("BL9 9SX");
            a.setCountry("United Kingdom");
        }
        return a;
    }

    private SaleOrderRequest order(Long customerId, Address shipping) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customerId);
        req.setWarehouseId(warehouse.getId());
        req.setShippingAddress(shipping);
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.ONE);
        l.setUnitPrice(new BigDecimal("10"));
        req.setLines(List.of(l));
        return req;
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ").toLowerCase();
        }
    }
}
