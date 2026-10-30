package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.*;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.AuditService;
import com.experterp.backend.service.OrganisationSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Turns one Shopify order into a sale order, for the current tenant. Used by webhooks and the catch-up import.
 * <ul>
 *   <li>Idempotent: the order is found again by its Shopify id, so repeats update instead of duplicating.</li>
 *   <li>Paid and nothing to check → CONFIRMED (ready to ship). Unpaid → DRAFT.</li>
 *   <li>Unknown SKU, no SKU or another currency → still imported, as DRAFT marked "needs review", with the reason.</li>
 *   <li>Once the order has left DRAFT in the ERP, Shopify updates no longer change it (fulfilment and
 *       cancellation sync come in 7.3 / 7.4).</li>
 * </ul>
 * Callers set the tenant and the audit actor.
 */
@Service
@RequiredArgsConstructor
public class ShopifyOrderImporter {

    public enum Outcome { CREATED, UPDATED, UNCHANGED, SKIPPED }

    public record Result(Outcome outcome, Long saleOrderId, String orderName, boolean needsReview, String reviewReason) {}

    private final SaleOrderRepository saleOrderRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final CustomerRepository customerRepository;
    private final ShopifyConnectionRepository connectionRepository;
    private final AuditService auditService;
    private final OrganisationSettingsService settingsService;

    // Currency the ERP's prices are in
    @Value("${app.currency:GBP}")
    private String erpCurrency;

    @Transactional
    public Result importOrder(ShopifyOrder o) {
        ShopifyConnection conn = connectionRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("No Shopify store is connected"));
        SaleOrder so = saleOrderRepository.findBySourceAndExternalId(SaleOrder.Source.SHOPIFY, o.id()).orElse(null);
        boolean isNew = so == null;

        if (!isNew && so.getStatus() != Status.DRAFT) {
            return new Result(Outcome.UNCHANGED, so.getId(), o.name(), so.isNeedsReview(), so.getReviewReason());
        }
        if (o.cancelled()) {
            if (isNew) return new Result(Outcome.SKIPPED, null, o.name(), false, "cancelled in Shopify");
            so.setStatus(Status.CANCELLED);
            so.setCancelledAt(LocalDateTime.now());
            auditService.log("SALE_ORDER", so.getId(), "CANCEL", Map.of("shopifyOrder", String.valueOf(o.name()), "reason", "cancelled in Shopify"));
            return new Result(Outcome.UPDATED, so.getId(), o.name(), so.isNeedsReview(), so.getReviewReason());
        }

        if (isNew) {
            so = new SaleOrder();
            so.setSource(SaleOrder.Source.SHOPIFY);
            so.setExternalId(o.id());
            so.setOrderNumber(settingsService.nextSaleOrderNumber());
            // Web orders are credited to the organisation's web sales rep (docs/business-rules.md §4)
            so.setSalesRep(settingsService.webSalesRep());
            if (o.createdAt() != null) {
                so.setCreatedAt(o.createdAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
            }
        }
        so.setExternalReference(o.name());
        so.setCustomerReference(o.name());
        so.setWarehouse(warehouse(conn));
        Customer customer = customer(o);
        so.setCustomer(customer);
        so.setCustomerName(customer.getName());
        so.setNotes("Imported from Shopify order " + (o.name() == null ? o.id() : o.name()));

        List<String> issues = new ArrayList<>();
        if (o.currency() != null && !o.currency().equalsIgnoreCase(erpCurrency)) {
            issues.add("order is in " + o.currency() + ", ERP prices are in " + erpCurrency);
        }

        // DRAFT orders have shipped nothing, so their lines can be rebuilt from Shopify
        so.getLines().clear();
        for (ShopifyOrder.Line l : o.lines()) {
            if (l.quantity().signum() <= 0) continue;
            String label = "'" + (l.title() == null ? "item" : l.title()) + "' × " + l.quantity().stripTrailingZeros().toPlainString();
            if (l.sku() == null) {
                issues.add(label + " has no SKU in Shopify");
                continue;
            }
            Optional<Product> product = productRepository.findBySku(l.sku());
            if (product.isEmpty()) {
                issues.add(label + ": SKU " + l.sku() + " is not in the ERP");
                continue;
            }
            SaleOrderLine line = new SaleOrderLine();
            line.setSaleOrder(so);
            line.setProduct(product.get());
            line.setQuantity(l.quantity());
            line.setUnitPrice(l.unitPrice());
            line.setExternalLineId(l.id());
            // No VAT recorded until it is settled whether Shopify prices include VAT (phase F)
            so.getLines().add(line);
        }
        if (so.getLines().isEmpty() && issues.isEmpty()) issues.add("the order has no items");

        so.setNeedsReview(!issues.isEmpty());
        so.setReviewReason(issues.isEmpty() ? null : String.join("; ", issues));
        if (issues.isEmpty() && o.isPaid()) {
            so.setStatus(Status.CONFIRMED);
            so.setConfirmedAt(LocalDateTime.now());
        } else {
            so.setStatus(Status.DRAFT);
        }
        saleOrderRepository.save(so);

        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("shopifyOrder", String.valueOf(o.name()));
        changes.put("status", so.getStatus());
        changes.put("lines", so.getLines().size());
        if (so.isNeedsReview()) changes.put("needsReview", so.getReviewReason());
        auditService.log("SALE_ORDER", so.getId(), isNew ? "IMPORT" : "IMPORT_UPDATE", changes);

        return new Result(isNew ? Outcome.CREATED : Outcome.UPDATED, so.getId(), o.name(), so.isNeedsReview(), so.getReviewReason());
    }

    private Warehouse warehouse(ShopifyConnection conn) {
        if (conn.getDefaultWarehouse() != null) return conn.getDefaultWarehouse();
        return warehouseRepository.findFirstByOrderByIdAsc().orElseThrow(() ->
                new IllegalStateException("No warehouse exists to ship Shopify orders from. Create one, then choose it on the Shopify page."));
    }

    /** Same Shopify customer → same ERP customer; otherwise an existing customer with that email; otherwise a new one. */
    private Customer customer(ShopifyOrder o) {
        ShopifyOrder.Customer sc = o.customer();
        if (sc != null) {
            Optional<Customer> known = customerRepository.findBySourceAndExternalId(Customer.Source.SHOPIFY, sc.id());
            if (known.isPresent()) return known.get();
        }
        ShopifyOrder.Address ship = o.shippingAddress();
        String email = sc != null && sc.email() != null ? sc.email() : o.email();
        String phone = sc != null && sc.phone() != null ? sc.phone() : o.phone() != null ? o.phone()
                : ship != null ? ship.phone() : null;
        if (email != null) {
            List<Customer> byEmail = customerRepository.findByContactEmail(email, PageRequest.of(0, 1));
            if (!byEmail.isEmpty()) return byEmail.get(0);
        }

        Customer c = new Customer();
        String name = sc != null ? sc.displayName() : null;
        c.setName(name != null ? name : email != null ? email : "Shopify customer " + (sc != null ? sc.id() : o.name()));
        c.setSource(Customer.Source.SHOPIFY);
        c.setExternalId(sc != null ? sc.id() : null);
        c.setCurrency(o.currency());
        if (ship != null) c.setAddress(ship.formatted());
        if (email != null || phone != null) {
            CustomerContact contact = new CustomerContact();
            contact.setName(name);
            contact.setEmail(email);
            contact.setPhone(phone);
            contact.setDefaultContact(true);
            c.replaceContacts(new ArrayList<>(List.of(contact)));
        }
        customerRepository.save(c);
        auditService.log("CUSTOMER", c.getId(), "CREATE", Map.of("name", c.getName(), "via", "Shopify order " + o.name()));
        return c;
    }
}
