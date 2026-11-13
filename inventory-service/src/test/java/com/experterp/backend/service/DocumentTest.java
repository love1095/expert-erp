package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.*;
import com.experterp.backend.entity.*;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static java.util.Arrays.stream;

/** Phase D5: quotes, sale orders, invoices, credit notes and purchase orders as PDF (docs/phase-d-sales.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class DocumentTest {

    @Autowired DocumentService documentService;
    @Autowired CompanyDetailsService companyDetailsService;
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
    @Autowired DocumentTemplateService templateService;

    Warehouse warehouse;
    Product helmet;
    Customer customer;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Docs " + id);
        t.setSlug("docs-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse");
        warehouse = warehouseRepository.save(warehouse);
        helmet = new Product();
        helmet.setSku("PW54-WHR");
        helmet.setName("Endurance helmet £ white");
        helmet.setUnit("Each");
        helmet = productRepository.save(helmet);
        movementService.createMovement(helmet, warehouse, BigDecimal.TEN, MovementType.ADJUSTMENT_IN, ReferenceType.MANUAL, "T", null);
        customer = new Customer();
        customer.setName("Loudfoot Builders");
        CustomerAddress billing = new CustomerAddress();
        billing.setAddress(new Address("12 High Street", null, "Rochdale", null, "OL11 1AA", null));
        billing.setDefaultForType(true);
        customer.replaceAddresses(new java.util.ArrayList<>(List.of(billing)));
        customer.setTaxNumber("GB123456789");
        customer = customerRepository.save(customer);
        companyDetailsService.update(new CompanyDetailsService.Details("Expert Safety Ltd", "Unit 1, Mill Lane\nRochdale\nOL11 1NS",
                "01706 000000", "sales@example.com", "www.example.com", "GB 999 9999 73", "01234567",
                "Account: Expert Safety Ltd\nSort code: 00-00-00  Account: 12345678", "Goods remain our property until paid for.", false));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anInvoiceShowsTheCompanyTheCustomerVatAndHowToPay() throws IOException {
        SaleOrderResponse so = shippedOrder();
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(so.getId(), all);

        DocumentService.Pdf pdf = documentService.invoice(inv.getId());
        assertThat(pdf.fileName()).isEqualTo(inv.getInvoiceNumber() + ".pdf");
        String text = text(pdf.bytes());
        assertThat(text).contains(lc("Invoice", inv.getInvoiceNumber(), "Expert Safety Ltd", "OL11 1NS", "VAT no. GB 999 9999 73",
                "Company no. 01234567", "Invoice to", "Loudfoot Builders", "Rochdale", "VAT no. GB123456789",
                "PW54-WHR", "Endurance helmet £ white", "Carriage", so.getOrderNumber(),
                // 4 × £10 + £5 carriage = £45, 20 % VAT £9
                "£45.00", "£9.00", "Total due", "£54.00",
                "Please pay", "quoting " + inv.getInvoiceNumber(), "Sort code: 00-00-00", "Goods remain our property", "Page 1 of 1"));
    }

    /** Phase I4: named templates like Cin7's ("Delivery Note", "Invoice - Cash Sale"), a default per type, one picked to print. */
    @Test
    void templatesLayTheDocumentOutADeliveryNoteHasNoPrices() throws IOException {
        SaleOrderResponse so = shippedOrder();
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(so.getId(), all);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            DocumentTemplateService.Template delivery = templateService.save(null, new DocumentTemplateService.Template(null,
                    DocumentTemplateService.DocType.SALES_ORDER, "Delivery Note - Expert Safety", false, "Delivery note", "#1f6feb",
                    false, false, true, true, true, "Please check the goods on delivery.", null, null, "Your delivery"));
            assertThat(delivery.isDefault()).isTrue();                              // the first of its type
            String note = text(documentService.saleOrder(so.getId()).bytes());      // printed with the default
            assertThat(note).contains(lc("Delivery note", so.getOrderNumber(), "PW54-WHR", "Please check the goods on delivery."))
                    .doesNotContain("£45.00").doesNotContain("£54.00").doesNotContain("carriage").doesNotContain("unit price");

            DocumentTemplateService.Template cash = templateService.save(null, new DocumentTemplateService.Template(null,
                    DocumentTemplateService.DocType.INVOICE, "Invoice - Cash Sale", false, null, "#d42020", true, true, true, true, true,
                    null, "Paid in full by card at the trade counter.", "Thank you for shopping with us.", null));
            String paid = text(documentService.invoice(inv.getId(), cash.id()).bytes());
            assertThat(paid).contains(lc("Invoice", "£54.00", "Paid in full by card at the trade counter.", "Thank you for shopping with us."))
                    .doesNotContain("sort code").doesNotContain("goods remain our property");

            // A template is for its own type; names are unique per type in any case
            assertThatThrownBy(() -> documentService.invoice(inv.getId(), delivery.id())).hasMessageContaining("not for invoices");
            assertThatThrownBy(() -> templateService.save(null, new DocumentTemplateService.Template(null,
                    DocumentTemplateService.DocType.INVOICE, "invoice - cash sale", false, null, null, true, true, true, true, true,
                    null, null, null, null))).hasMessageContaining("already");
            assertThatThrownBy(() -> templateService.save(null, new DocumentTemplateService.Template(null,
                    DocumentTemplateService.DocType.INVOICE, "Red", false, null, "red", true, true, true, true, true,
                    null, null, null, null))).hasMessageContaining("#d42020");

            // Without a template for credit notes: the standard layout; removing the default makes another the default
            DocumentTemplateService.Template second = templateService.save(null, new DocumentTemplateService.Template(null,
                    DocumentTemplateService.DocType.SALES_ORDER, "Pick List", true, "Pick list", "#000000", false, false, true, false, false,
                    null, null, null, null));
            assertThat(templateService.get(delivery.id()).isDefault()).isFalse();
            templateService.delete(second.id());
            assertThat(templateService.get(delivery.id()).isDefault()).isTrue();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void aCreditNoteNamesTheInvoiceItCredits() throws IOException {
        SaleOrderResponse so = shippedOrder();
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(so.getId(), all);
        CreditNoteRequest req = new CreditNoteRequest();
        CreditNoteRequest.Line l = new CreditNoteRequest.Line();
        l.setInvoiceItemId(inv.getItems().stream().filter(i -> i.getProductId() != null).findFirst().orElseThrow().getId());
        l.setQuantity(BigDecimal.ONE);
        req.setLines(List.of(l));
        req.setSettle(CreditNoteRequest.Settle.KEEP_AS_CREDIT);
        InvoiceResponse cn = creditNoteService.create(inv.getId(), req);

        String text = text(documentService.invoice(cn.getId()).bytes());
        assertThat(text).contains(lc("Credit note", cn.getInvoiceNumber(), "Credits invoice", inv.getInvoiceNumber(), "Credit to",
                "Total credit", "£12.00")).doesNotContain("please pay");
    }

    @Test
    void aQuoteAndADraftOrderAreTitledAndStamped() throws IOException {
        SaleOrderRequest req = order();
        req.setQuote(true);
        SaleOrderResponse quote = saleOrderService.create(req);
        assertThat(text(documentService.saleOrder(quote.getId()).bytes())).contains(lc("Quote", "Quote for", "Valid until", "Loudfoot Builders"));

        SaleOrderResponse draft = saleOrderService.create(order());
        assertThat(text(documentService.saleOrder(draft.getId()).bytes())).contains(lc("Sales order", draft.getOrderNumber(), "DRAFT", "£54.00"));
    }

    @Test
    void theContactChosenOnTheOrderIsPrintedOnTheOrderAndItsInvoice() throws IOException {
        SaleOrderRequest req = order();
        req.setContactName("Priya Site Manager");
        req.setContactEmail("priya@loudfoot.test");
        req.setContactPhone("0161 555 0101");
        SaleOrderResponse so = saleOrderService.create(req);
        assertThat(text(documentService.saleOrder(so.getId()).bytes())).contains(lc("Attn: Priya Site Manager", "priya@loudfoot.test", "0161 555 0101"));

        saleOrderService.confirm(so.getId());
        OrderInvoiceRequest all = new OrderInvoiceRequest();
        all.setIssue(true);
        InvoiceResponse inv = invoiceService.invoiceOrder(so.getId(), all);
        assertThat(text(documentService.invoice(inv.getId()).bytes())).contains(lc("Attn: Priya Site Manager", "priya@loudfoot.test"));
    }

    @Test
    void aPurchaseOrderShowsTheSuppliersCodesAndWhereToDeliver() throws IOException {
        Supplier portwest = new Supplier();
        portwest.setName("Portwest");
        portwest.setEmail("orders@portwest.example");
        portwest = supplierRepository.save(portwest);
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(portwest.getId());
        req.setWarehouseId(warehouse.getId());
        req.setOrdered(true);
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("5"));
        l.setUnitCost(new BigDecimal("65.50"));
        l.setSupplierSku("PW54WHR");
        req.setLines(List.of(l));
        PurchaseOrderResponse po = purchaseOrderService.create(req);

        String text = text(documentService.purchaseOrder(po.getId()).bytes());
        assertThat(text).contains(lc("Purchase order", po.getOrderNumber(), "Supplier", "Portwest", "orders@portwest.example",
                "Deliver to", "Expert Warehouse", "Supplier code", "PW54WHR", "5 Each", "£65.50", "£327.50"));
        // A supplier's bill is their document
        BillRequest bill = new BillRequest();
        bill.setSupplierInvoiceNumber("006387333");
        purchaseOrderService.receive(po.getId());
        InvoiceResponse b = invoiceService.billPurchaseOrder(po.getId(), bill);
        assertThatThrownBy(() -> documentService.invoice(b.getId())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tickedDocumentsPrintTogether() throws IOException {
        SaleOrderResponse a = saleOrderService.create(order());
        SaleOrderResponse b = saleOrderService.create(order());
        DocumentService.Pdf pdf = documentService.many("sale-orders", List.of(a.getId(), b.getId()));
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
        }
        assertThat(text(pdf.bytes())).contains(lc(a.getOrderNumber(), b.getOrderNumber()));
    }

    @Test
    void theLogoMustBeASmallPngOrJpeg() throws IOException {
        assertThatThrownBy(() -> companyDetailsService.setLogo("not an image".getBytes(), "image/png"))
                .isInstanceOf(IllegalArgumentException.class);
        BufferedImage img = new BufferedImage(120, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        assertThat(companyDetailsService.setLogo(png.toByteArray(), "image/png").hasLogo()).isTrue();
        // Printed with the logo
        assertThat(text(documentService.saleOrder(saleOrderService.create(order()).getId()).bytes())).contains(lc("Expert Safety Ltd"));
        assertThat(companyDetailsService.removeLogo().hasLogo()).isFalse();
    }

    // ---- helpers ----

    private SaleOrderRequest order() {
        SaleOrderRequest req = new SaleOrderRequest();
        req.setCustomerId(customer.getId());
        req.setWarehouseId(warehouse.getId());
        SaleOrderRequest.Line l = new SaleOrderRequest.Line();
        l.setProductId(helmet.getId());
        l.setQuantity(new BigDecimal("4"));
        l.setUnitPrice(BigDecimal.TEN);
        req.setLines(List.of(l));
        SaleOrderRequest.Charge c = new SaleOrderRequest.Charge();
        c.setDescription("Carriage");
        c.setUnitPrice(new BigDecimal("5"));
        req.setCharges(List.of(c));
        return req;
    }

    private SaleOrderResponse shippedOrder() {
        SaleOrderResponse so = saleOrderService.confirm(saleOrderService.create(order()).getId());
        return saleOrderService.fulfill(so.getId(), null);
    }

    private static String[] lc(String... values) {
        return stream(values).map(String::toLowerCase).toArray(String[]::new);
    }

    private static String text(byte[] pdf) throws IOException {
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            // Labels are upper case on the page and lines wrap: compare in lower case, spaces collapsed
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ").toLowerCase();
        }
    }
}
