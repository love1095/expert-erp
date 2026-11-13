package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.BillRequest;
import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.PurchaseReceiptRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.PurchaseOrder.Approach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** New purchase, the rest (like Cin7): Stock first / Invoice first, the default location, presets. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseApproachTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired OrganisationSettingsService settingsService;
    @Autowired PurchasePresetService presetService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Supplier orn;
    Product polo;

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString();
        Tenant t = new Tenant();
        t.setName("Approach " + id);
        t.setSlug("approach-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("user", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse = warehouseRepository.save(warehouse);
        orn = new Supplier();
        orn.setName("Orn Clothing " + id);
        orn = supplierRepository.save(orn);
        polo = new Product();
        polo.setSku("POLO-" + id.substring(0, 6));
        polo.setName("Polo");
        polo.setUnit("Each");
        polo.setCostPrice(new BigDecimal("10.00"));
        polo = productRepository.save(polo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void stockFirstBillsOnlyWhatCameIn() {
        PurchaseOrderResponse po = order(null);
        assertThat(po.getApproach()).isEqualTo(Approach.STOCK_FIRST);
        assertThatThrownBy(() -> bill(po, "INV-1", 4)).hasMessageContaining("Stock first").hasMessageContaining("Receive the goods first");

        receive(po, 3);
        assertThatThrownBy(() -> bill(po, "INV-1", 4)).hasMessageContaining("only 3 of " + polo.getSku());
        bill(po, "INV-1", 3);
        // Everything that came in is billed now
        assertThatThrownBy(() -> bill(po, "INV-2", 1)).hasMessageContaining("only 0");
        // Receiving is not held back by the bills
        receive(po, 7);
        assertThat(purchaseOrderService.findById(po.getId()).getStatus()).isEqualTo(PurchaseOrder.Status.RECEIVED);
    }

    @Test
    void invoiceFirstReceivesOnlyWhatIsBilled() {
        PurchaseOrderResponse po = order(Approach.INVOICE_FIRST);
        assertThat(po.getApproach()).isEqualTo(Approach.INVOICE_FIRST);
        assertThatThrownBy(() -> purchaseOrderService.receive(po.getId())).hasMessageContaining("enter the supplier's bill before receiving");

        bill(po, "INV-9", 6);
        assertThatThrownBy(() -> receive(po, 7)).hasMessageContaining("only 6 of " + polo.getSku() + " is billed and not received");
        // Receive everything outstanding = as much as is billed
        PurchaseOrderResponse received = purchaseOrderService.receive(po.getId());
        assertThat(received.getLines().get(0).getReceivedQuantity()).isEqualByComparingTo("6");
        assertThat(received.getStatus()).isEqualTo(PurchaseOrder.Status.ORDERED);
        assertThatThrownBy(() -> purchaseOrderService.receive(po.getId())).hasMessageContaining("Invoice first");
    }

    @Test
    void theOrganisationsDefaultsStartANewPurchase() {
        assertThat(settingsService.defaultPurchaseApproach()).isEqualTo(Approach.STOCK_FIRST);
        assertThat(settingsService.defaultWarehouseId()).isNull();

        OrganisationSettingsService.Settings s = settingsService.get();
        settingsService.update(withDefaults(s, "INVOICE_FIRST", warehouse.getId()));
        assertThat(settingsService.defaultPurchaseApproach()).isEqualTo(Approach.INVOICE_FIRST);
        assertThat(settingsService.defaultWarehouseId()).isEqualTo(warehouse.getId());
        assertThat(order(null).getApproach()).isEqualTo(Approach.INVOICE_FIRST);
        // The order's own choice wins, and an edit keeps it when none is sent
        PurchaseOrderResponse own = order(Approach.STOCK_FIRST);
        assertThat(own.getApproach()).isEqualTo(Approach.STOCK_FIRST);

        assertThatThrownBy(() -> settingsService.update(withDefaults(s, "LATER", null))).hasMessageContaining("STOCK_FIRST or INVOICE_FIRST");
        assertThatThrownBy(() -> settingsService.update(withDefaults(s, null, 999_999_999L))).hasMessageContaining("default location");
        settingsService.update(withDefaults(s, null, null));
        assertThat(settingsService.defaultWarehouseId()).isNull();
        assertThat(settingsService.defaultPurchaseApproach()).isEqualTo(Approach.INVOICE_FIRST);
    }

    @Test
    void anEditKeepsTheApproachWhenNoneIsSent() {
        PurchaseOrderRequest req = request(Approach.INVOICE_FIRST, false);
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        req.setApproach(null);
        assertThat(purchaseOrderService.update(po.getId(), req).getApproach()).isEqualTo(Approach.INVOICE_FIRST);
    }

    @Test
    void aPresetKeepsTheHeaderUnderItsNameAndIsReplacedByName() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("supplierName", orn.getName());
        data.put("warehouseId", warehouse.getId());
        data.put("approach", "INVOICE_FIRST");
        data.put("blindReceipt", true);
        data.put("paymentTerm", "");
        data.put("lines", List.of(Map.of("productId", 1)));
        PurchasePresetService.Preset p = presetService.save("  Orn monthly ", data);
        assertThat(p.name()).isEqualTo("Orn monthly");
        assertThat(p.data().get("supplierName").asText()).isEqualTo(orn.getName());
        assertThat(p.data().get("blindReceipt").asBoolean()).isTrue();
        // Blank fields and anything not part of the header are dropped
        assertThat(p.data().has("paymentTerm")).isFalse();
        assertThat(p.data().has("lines")).isFalse();

        PurchasePresetService.Preset replaced = presetService.save("ORN MONTHLY", Map.of("supplierName", "Other"));
        assertThat(replaced.id()).isEqualTo(p.id());
        assertThat(presetService.list()).hasSize(1);
        assertThat(presetService.list().get(0).data().get("supplierName").asText()).isEqualTo("Other");

        assertThatThrownBy(() -> presetService.save(" ", data)).hasMessageContaining("name");
        assertThatThrownBy(() -> presetService.save("Empty", Map.of("notes", ""))).hasMessageContaining("Fill in");
        presetService.delete(p.id());
        assertThat(presetService.list()).isEmpty();
        assertThatThrownBy(() -> presetService.delete(p.id())).hasMessageContaining("not found");
    }

    private static OrganisationSettingsService.Settings withDefaults(OrganisationSettingsService.Settings s, String approach, Long warehouseId) {
        return new OrganisationSettingsService.Settings(s.saleOrderPrefix(), s.nextSaleOrderNumber(), s.webSalesRep(), s.defaultSaleTaxRule(),
                s.quoteValidDays(), s.invoicePrefix(), s.nextInvoiceNumber(), s.autoInvoiceOnShipment(), s.creditNotePrefix(),
                s.nextCreditNoteNumber(), s.purchaseOrderPrefix(), s.nextPurchaseOrderNumber(), s.defaultPurchaseTaxRule(),
                s.billPrefix(), s.nextBillNumber(), approach, warehouseId);
    }

    private PurchaseOrderResponse order(Approach approach) {
        return purchaseOrderService.create(request(approach, true));
    }

    private PurchaseOrderRequest request(Approach approach, boolean ordered) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(orn.getId());
        req.setWarehouseId(warehouse.getId());
        req.setApproach(approach);
        req.setOrdered(ordered);
        PurchaseOrderRequest.Line line = new PurchaseOrderRequest.Line();
        line.setProductId(polo.getId());
        line.setQuantity(new BigDecimal("10"));
        req.setLines(List.of(line));
        return req;
    }

    private void receive(PurchaseOrderResponse po, int qty) {
        PurchaseReceiptRequest r = new PurchaseReceiptRequest();
        PurchaseReceiptRequest.Line l = new PurchaseReceiptRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(0).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        r.setLines(List.of(l));
        purchaseOrderService.receive(po.getId(), r);
    }

    private void bill(PurchaseOrderResponse po, String number, int qty) {
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber(number);
        BillRequest.Line l = new BillRequest.Line();
        l.setPurchaseOrderLineId(po.getLines().get(0).getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        b.setLines(List.of(l));
        invoiceService.billPurchaseOrder(po.getId(), b);
    }
}
