package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.dto.PurchaseOrderRequest;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.PurchaseOrder.Status;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase E1: the purchase order like Cin7 (docs/phase-e-purchasing.md). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class PurchaseOrderTest {

    @Autowired PurchaseOrderService purchaseOrderService;
    @Autowired OrganisationSettingsService settingsService;
    @Autowired StockMovementService movementService;
    @Autowired ProductRepository productRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired WarehouseRepository warehouseRepository;
    @Autowired TenantRepository tenantRepository;

    Warehouse warehouse;
    Supplier orn;
    Product bodywarmer;
    Product hoodie;

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString();
        t.setName("Purchasing " + id);
        t.setSlug("purchasing-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        warehouse = new Warehouse();
        warehouse.setName("Expert Warehouse " + id);
        warehouse = warehouseRepository.save(warehouse);
        orn = new Supplier();
        orn.setName("Orn Clothing");
        orn.setPaymentTerm("30 Day EOM");
        orn = supplierRepository.save(orn);
        // As imported from Cin7: Orn is the default supplier, with its code and price
        bodywarmer = product("WWJC-3200-BK-L", "ORN Albatross Fleece", "3200.BK.L", "9.21", "8.00");
        hoodie = product("WWSH-1280-BK-3XL", "ORN Owl Hoodie", "1280.BK.3XL", "9.41", "9.00");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anOrderTakesTheSuppliersTermsCodesPricesAndVatAndTotalsLikeCin7() {
        PurchaseOrderRequest req = request(line(bodywarmer, 5), line(hoodie, 3));
        PurchaseOrderRequest.Cost freight = new PurchaseOrderRequest.Cost();
        freight.setDescription("Freight");
        freight.setUnitPrice(new BigDecimal("10.00"));
        req.setCosts(List.of(freight));

        PurchaseOrderResponse po = purchaseOrderService.create(req);

        assertThat(po.getOrderNumber()).startsWith("PO-");
        assertThat(po.getStatus()).isEqualTo(Status.DRAFT);
        assertThat(po.getSupplierName()).isEqualTo("Orn Clothing");
        assertThat(po.getPaymentTerm()).isEqualTo("30 Day EOM");
        assertThat(po.getLines()).extracting(PurchaseOrderResponse.Line::getSupplierSku).containsExactly("3200.BK.L", "1280.BK.3XL");
        assertThat(po.getLines()).extracting(l -> l.getUnitCost().stripTrailingZeros().toPlainString()).containsExactly("9.21", "9.41");
        assertThat(po.getLines()).allSatisfy(l -> assertThat(l.getTaxName()).isEqualTo("20% (VAT on Expenses)"));
        // 5 × 9.21 + 3 × 9.41 = 74.28, VAT 14.86; freight 10.00, VAT 2.00
        assertThat(po.getLinesNet()).isEqualByComparingTo("74.28");
        assertThat(po.getLinesTax()).isEqualByComparingTo("14.86");
        assertThat(po.getCostsNet()).isEqualByComparingTo("10.00");
        assertThat(po.getCostsTax()).isEqualByComparingTo("2.00");
        assertThat(po.getTotal()).isEqualByComparingTo("101.14");
    }

    @Test
    void theProductsPurchaseRuleComesBeforeTheSuppliersAndAnotherSuppliersPricesAreNotUsed() {
        orn.setTaxRule("Zero Rated Expenses");
        supplierRepository.save(orn);
        hoodie.getDetails().setPurchaseTaxRule("5% (VAT on Expenses)");
        productRepository.save(hoodie);
        PurchaseOrderResponse po = purchaseOrderService.preview(request(line(bodywarmer, 1), line(hoodie, 1)));
        assertThat(po.getLines()).extracting(PurchaseOrderResponse.Line::getTaxName)
                .containsExactly("Zero Rated Expenses", "5% (VAT on Expenses)");

        Supplier other = new Supplier();
        other.setName("Another Supplier");
        other = supplierRepository.save(other);
        PurchaseOrderRequest req = request(line(bodywarmer, 1));
        req.setSupplierId(other.getId());
        PurchaseOrderResponse fromOther = purchaseOrderService.preview(req);
        // Not Orn's price or code: the product's cost price
        assertThat(fromOther.getLines().get(0).getUnitCost()).isEqualByComparingTo("8.00");
        assertThat(fromOther.getLines().get(0).getSupplierSku()).isNull();
    }

    @Test
    void numbersCarryOnFromTheSettingAndADraftIsEditedUntilItIsOrdered() {
        OrganisationSettingsService.Settings s = settingsService.get();
        settingsService.update(new OrganisationSettingsService.Settings(s.saleOrderPrefix(), s.nextSaleOrderNumber(), s.webSalesRep(),
                s.defaultSaleTaxRule(), s.quoteValidDays(), s.invoicePrefix(), s.nextInvoiceNumber(), s.autoInvoiceOnShipment(),
                s.creditNotePrefix(), s.nextCreditNoteNumber(), "PO-", 19074, s.defaultPurchaseTaxRule()));

        PurchaseOrderResponse po = purchaseOrderService.create(request(line(bodywarmer, 5)));
        assertThat(po.getOrderNumber()).isEqualTo("PO-19074");

        PurchaseOrderRequest changed = request(line(bodywarmer, 6));
        changed.setRequiredBy(LocalDate.of(2026, 11, 15));
        changed.setSupplierReference("ORN-55");
        PurchaseOrderResponse edited = purchaseOrderService.update(po.getId(), changed);
        assertThat(edited.getOrderNumber()).isEqualTo("PO-19074");
        assertThat(edited.getLines()).singleElement().satisfies(l -> assertThat(l.getQuantity()).isEqualByComparingTo("6"));
        assertThat(edited.getRequiredBy()).isEqualTo(LocalDate.of(2026, 11, 15));

        PurchaseOrderResponse ordered = purchaseOrderService.markOrdered(po.getId());
        assertThat(ordered.getStatus()).isEqualTo(Status.ORDERED);
        assertThat(ordered.getOrderedAt()).isNotNull();
        assertThatThrownBy(() -> purchaseOrderService.update(po.getId(), changed)).isInstanceOf(IllegalStateException.class);
        assertThat(purchaseOrderService.findAll(0, 20, null, "orn-55").getContent()).extracting(PurchaseOrderResponse::getId)
                .containsExactly(po.getId());

        // Received at the line cost; a received order can't be cancelled
        purchaseOrderService.receive(po.getId());
        assertThat(movementService.getStockLevel(bodywarmer.getId(), warehouse.getId()).getStock()).isEqualByComparingTo("6");
        assertThatThrownBy(() -> purchaseOrderService.cancel(po.getId())).isInstanceOf(IllegalStateException.class);
        PurchaseOrderResponse second = purchaseOrderService.create(request(line(hoodie, 1)));
        assertThat(second.getOrderNumber()).isEqualTo("PO-19075");
        assertThat(purchaseOrderService.cancel(purchaseOrderService.markOrdered(second.getId()).getId()).getStatus()).isEqualTo(Status.CANCELLED);
    }

    private Product product(String sku, String name, String supplierCode, String supplierPrice, String costPrice) {
        Product p = new Product();
        p.setSku(sku);
        p.setName(name);
        p.setUnit("Each");
        p.setDefaultSupplier(orn);
        p.setSupplierProductCode(supplierCode);
        p.setCostPrice(new BigDecimal(costPrice));
        p.getDetails().setSupplierPrice(new BigDecimal(supplierPrice));
        return productRepository.save(p);
    }

    private PurchaseOrderRequest request(PurchaseOrderRequest.Line... lines) {
        PurchaseOrderRequest req = new PurchaseOrderRequest();
        req.setSupplierId(orn.getId());
        req.setWarehouseId(warehouse.getId());
        req.setLines(List.of(lines));
        return req;
    }

    private static PurchaseOrderRequest.Line line(Product p, int qty) {
        PurchaseOrderRequest.Line l = new PurchaseOrderRequest.Line();
        l.setProductId(p.getId());
        l.setQuantity(BigDecimal.valueOf(qty));
        return l;
    }
}
