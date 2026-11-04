package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase H3 (spec §6): the VAT return in HMRC's nine boxes, standard scheme, reverse charge and import VAT. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class VatReturnTest {

    @Autowired VatReturnService vatReturnService;
    @Autowired TaxService taxService;
    @Autowired SaleOrderService saleOrderService;
    @Autowired InvoiceService invoiceService;
    @Autowired CreditNoteService creditNoteService;
    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Warehouse warehouse;
    Product helmet;
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("VAT " + id);
        t.setSlug("vat-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
        warehouse = new Warehouse();
        warehouse.setName("Main");
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("HELM-" + id.substring(0, 6));
        helmet.setName("Helmet");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.valueOf(100), MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "T", null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void theRatesKnowTheirVatBoxesAndReverseChargeAddsNothingToTheBill() {
        TaxRate reverse = taxService.rate(TaxRate.Usage.PURCHASE, "Reverse Charge Expenses (20%)");
        assertThat(reverse.getVatType()).isEqualTo(TaxRate.VatType.REVERSE_CHARGE);
        assertThat(reverse.getRate()).isEqualByComparingTo("0");
        assertThat(reverse.getReportRate()).isEqualByComparingTo("20");
        assertThat(taxService.rate(TaxRate.Usage.PURCHASE, "Full VAT").getVatType()).isEqualTo(TaxRate.VatType.FULL_VAT);
        assertThat(taxService.rate(TaxRate.Usage.SALE, "No VAT").getVatType()).isEqualTo(TaxRate.VatType.NO_VAT);
        assertThat(taxService.rate(TaxRate.Usage.SALE, "Zero Rated Income").getVatType()).isEqualTo(TaxRate.VatType.ZERO);
    }

    @Test
    void salesPurchasesCreditNotesReverseChargeAndImportVatFillTheNineBoxes() {
        // Sales: £100 + £20 VAT; £50 zero-rated; credit note −£10 − £2 VAT
        Customer standard = customer("Loudfoot Builders", null);
        InvoiceResponse sale = invoice(standard, 10, "10");
        invoice(customer("Export Co", "Zero Rated Income"), 5, "10");
        CreditNoteRequest cn = new CreditNoteRequest();
        CreditNoteRequest.Line line = new CreditNoteRequest.Line();
        line.setInvoiceItemId(sale.getItems().get(0).getId());
        line.setQuantity(BigDecimal.ONE);
        cn.setLines(List.of(line));
        creditNoteService.create(sale.getId(), cn);
        // Outside the period, a draft and a cancelled invoice: not counted
        InvoiceResponse old = invoice(standard, 1, "999");
        jdbc.update("UPDATE invoices SET issue_date = ? WHERE id = ?", today.minusYears(1), old.getId());
        InvoiceResponse cancelled = invoice(standard, 1, "777");
        jdbc.update("UPDATE invoices SET status = 'CANCELLED' WHERE id = ?", cancelled.getId());

        // Purchases: 3 × £65.50 + 20 %; import VAT £25 (all VAT); £100 reverse-charge service (nothing charged)
        InvoiceResponse bill = bill(charge("Import VAT (C79)", "25", "Full VAT"), charge("Design service (EU supplier)", "100", "Reverse Charge Expenses (20%)"));
        assertThat(bill.getSubtotal()).isEqualByComparingTo("296.50");
        assertThat(bill.getTaxTotal()).isEqualByComparingTo("64.30");
        assertThat(bill.getTotalAmount()).isEqualByComparingTo("360.80");

        VatReturnService.VatReturn r = vatReturnService.vatReturn(today.withDayOfMonth(1), today);
        VatReturnService.Boxes b = r.boxes();
        assertThat(b.box1()).isEqualByComparingTo("38.00");     // 20 − 2 + 20 reverse charge
        assertThat(b.box2()).isEqualByComparingTo("0");
        assertThat(b.box3()).isEqualByComparingTo("38.00");
        assertThat(b.box4()).isEqualByComparingTo("84.30");     // 39.30 + 25 import VAT + 20 reverse charge
        assertThat(b.box5()).isEqualByComparingTo("46.30");
        assertThat(r.refund()).isTrue();
        assertThat(b.box6()).isEqualByComparingTo("140");       // 100 + 50 − 10
        assertThat(b.box7()).isEqualByComparingTo("296");       // 296.50, pence dropped
        assertThat(b.box9()).isEqualByComparingTo("0");
        assertThat(r.rates()).filteredOn(x -> x.vatType() == TaxRate.VatType.REVERSE_CHARGE).singleElement()
                .satisfies(x -> assertThat(x.vat()).isEqualByComparingTo("20.00"));
        assertThat(r.documents()).extracting(VatReturnService.Document::number).doesNotContain(old.getInvoiceNumber(), cancelled.getInvoiceNumber());
        assertThat(r.documents()).filteredOn(d -> d.kind().equals("CREDIT_NOTE")).singleElement()
                .satisfies(d -> assertThat(d.vat()).isEqualByComparingTo("-2.00"));

        assertThatThrownBy(() -> vatReturnService.vatReturn(today, today.minusDays(1))).hasMessageContaining("starts after it ends");
    }

    // ---- helpers ----

    private Customer customer(String name, String taxRule) {
        Customer c = new Customer();
        c.setName(name);
        c.setTaxRule(taxRule);
        return customerRepository.save(c);
    }

    private InvoiceResponse invoice(Customer c, int qty, String price) {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(c.getId());
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        l.setUnitPrice(new BigDecimal(price));
        req.setLines(List.of(l));
        Long so = saleOrderService.confirm(saleOrderService.create(req).getId(), "VAT test").getId();
        saleOrderService.fulfill(so, null);
        OrderInvoiceRequest issue = new OrderInvoiceRequest();
        issue.setIssue(true);
        return invoiceService.invoiceOrder(so, issue);
    }

    private static BillRequest.Charge charge(String description, String amount, String taxRule) {
        BillRequest.Charge c = new BillRequest.Charge();
        c.setDescription(description);
        c.setUnitPrice(new BigDecimal(amount));
        c.setTaxRule(taxRule);
        return c;
    }

    private InvoiceResponse bill(BillRequest.Charge... charges) {
        Supplier s = new Supplier();
        s.setName("Portwest Clothing Ltd");
        s = supplierRepository.save(s);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(s.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(BigDecimal.valueOf(3));
        l.setUnitCost(new BigDecimal("65.50"));
        req.setLines(List.of(l));
        PurchaseOrderResponse po = purchaseOrderService.create(req);
        purchaseOrderService.receive(po.getId());
        BillRequest b = new BillRequest();
        b.setSupplierInvoiceNumber("006387333");
        b.setCharges(List.of(charges));
        return invoiceService.billPurchaseOrder(po.getId(), b);
    }
}
