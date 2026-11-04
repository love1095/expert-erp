package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.*;
import com.experterp.backend.entity.SaleOrder.Status;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.ShopifyRefundRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.AuditService;
import com.experterp.backend.service.OrganisationSettingsService;
import com.experterp.backend.service.SaleAmounts;
import com.experterp.backend.service.SaleOrderService;
import com.experterp.backend.service.TaxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Turns one Shopify order into a sale order, for the current tenant. Used by webhooks and the catch-up import.
 * <ul>
 *   <li>Idempotent: the order is found again by its Shopify id, so repeats update instead of duplicating.</li>
 *   <li>Paid and nothing to check → CONFIRMED (ready to ship). Unpaid → DRAFT.</li>
 *   <li>Unknown SKU, no SKU or another currency → still imported, as DRAFT marked "needs review", with the reason.</li>
 *   <li>Prices include VAT (business-rules.md §5): each line and shipping charge is split into net + VAT with its
 *       rate, so the ERP total is what the customer paid; a penny of VAT rounding is shown as a rounding charge,
 *       anything bigger puts the order up for review.</li>
 *   <li>Once the order has left DRAFT in the ERP, Shopify updates no longer change its lines. Two things still
 *       come through (7.4): a cancellation, when nothing has shipped yet, cancels the ERP order; refunds are kept on
 *       the order to be reviewed (a person makes the credit note).</li>
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
    private final TaxService taxService;
    private final SaleOrderService saleOrderService;
    private final ShopifyRefundRepository refundRepository;
    private final ObjectMapper objectMapper;

    // Up to this much under Shopify's total is VAT rounding, made up by a rounding charge; more needs a person
    static final BigDecimal ROUNDING_TOLERANCE = new BigDecimal("0.05");
    static final String ROUNDING_CHARGE = "Rounding to Shopify's total (VAT included in prices)";

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
            boolean changed = recordRefunds(so, o);
            if (o.cancelled() && cancelUnshipped(so, o)) changed = true;
            return new Result(changed ? Outcome.UPDATED : Outcome.UNCHANGED, so.getId(), o.name(), so.isNeedsReview(), so.getReviewReason());
        }
        if (o.cancelled()) {
            if (isNew) return new Result(Outcome.SKIPPED, null, o.name(), false, "cancelled in Shopify");
            so.setStatus(Status.CANCELLED);
            so.setCancelledAt(LocalDateTime.now());
            auditService.log("SALE_ORDER", so.getId(), "CANCEL", Map.of("shopifyOrder", String.valueOf(o.name()), "reason", "cancelled in Shopify"));
            recordRefunds(so, o);
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
            so.setOrderedAt(so.getCreatedAt());
        }
        so.setExternalReference(o.name());
        so.setCustomerReference(o.name());
        so.setWarehouse(warehouse(conn));
        Customer customer = customer(o);
        so.setCustomer(customer);
        so.setCustomerName(customer.getName());
        so.setNotes("Imported from Shopify order " + (o.name() == null ? o.id() : o.name()));
        // G4: delivered where the Shopify order says; billed at the customer's billing address, else there too
        Address ship = address(o.shippingAddress());
        so.setShippingAddress(ship);
        so.setShipToContact(o.shippingAddress() != null ? o.shippingAddress().name() : null);
        so.setBillingAddress(customer.billingAddress().map(Address::new).orElse(ship == null ? null : new Address(ship)));

        List<String> issues = new ArrayList<>();
        if (o.currency() != null && !o.currency().equalsIgnoreCase(erpCurrency)) {
            issues.add("order is in " + o.currency() + ", ERP prices are in " + erpCurrency);
        }

        // DRAFT orders have shipped nothing, so their lines can be rebuilt from Shopify
        so.getLines().clear();
        so.getCharges().clear();
        BigDecimal shortBy = BigDecimal.ZERO;
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
            line.setExternalLineId(l.id());
            // The ERP's VAT rule for the product and customer (business-rules.md §3)
            TaxRate rate = taxService.resolveSale(product.get(), customer);
            line.setTaxName(rate.getName());
            line.setTaxRate(rate.getRate());
            if (o.taxesIncluded()) {
                VatIncluded.Split split = VatIncluded.split(l.amount(), l.quantity(), rate.getRate());
                line.setUnitPrice(split.unitNet());
                shortBy = shortBy.add(split.shortBy());
            } else {
                line.setUnitPrice(l.unitPrice());
            }
            so.getLines().add(line);
        }
        if (so.getLines().isEmpty() && issues.isEmpty()) issues.add("the order has no items");

        // Delivery charged in Shopify
        TaxRate chargeRate = taxService.resolveSale(null, customer);
        for (ShopifyOrder.Shipping sh : o.shipping()) {
            if (sh.price() == null || sh.price().signum() <= 0) continue;
            SaleOrderCharge charge = new SaleOrderCharge();
            charge.setSaleOrder(so);
            charge.setDescription("Shipping" + (sh.title() == null ? "" : ": " + sh.title()));
            charge.setTaxName(chargeRate.getName());
            charge.setTaxRate(chargeRate.getRate());
            if (o.taxesIncluded()) {
                VatIncluded.Split split = VatIncluded.split(sh.price(), BigDecimal.ONE, chargeRate.getRate());
                charge.setUnitPrice(split.unitNet());
                shortBy = shortBy.add(split.shortBy());
            } else {
                charge.setUnitPrice(sh.price());
            }
            so.getCharges().add(charge);
        }
        // The ERP total must be what the customer paid
        if (issues.isEmpty() && o.totalPrice() != null) {
            BigDecimal diff = o.totalPrice().subtract(erpTotal(so));
            if (diff.signum() > 0 && diff.compareTo(ROUNDING_TOLERANCE) <= 0) {
                SaleOrderCharge rounding = new SaleOrderCharge();
                rounding.setSaleOrder(so);
                rounding.setDescription(ROUNDING_CHARGE);
                rounding.setUnitPrice(diff);
                rounding.setTaxName("No VAT");
                rounding.setTaxRate(BigDecimal.ZERO);
                so.getCharges().add(rounding);
            } else if (diff.signum() != 0) {
                issues.add("the ERP total " + money(erpTotal(so)) + " is not Shopify's " + money(o.totalPrice())
                        + " (a discount, tip or tax the ERP does not know about?)");
            }
        }

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
        recordRefunds(so, o);

        return new Result(isNew ? Outcome.CREATED : Outcome.UPDATED, so.getId(), o.name(), so.isNeedsReview(), so.getReviewReason());
    }

    /**
     * Keeps each refund Shopify reports that the ERP has not seen, for review on the order (7.4). Nothing is credited
     * here: a person decides (business-rules.md §5). True when one was added.
     */
    private boolean recordRefunds(SaleOrder so, ShopifyOrder o) {
        boolean added = false;
        for (ShopifyOrder.Refund r : o.refunds()) {
            if (r.id() == null || refundRepository.existsByRefundId(r.id())) continue;
            List<Map<String, Object>> lines = new ArrayList<>();
            for (ShopifyOrder.RefundLine rl : r.lines()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("lineItemId", rl.lineItemId());
                SaleOrderLine line = so.getLines().stream().filter(x -> rl.lineItemId() != null && rl.lineItemId().equals(x.getExternalLineId()))
                        .findFirst().orElse(null);
                ShopifyOrder.Line shopifyLine = o.lines().stream().filter(x -> rl.lineItemId() != null && rl.lineItemId().equals(x.id()))
                        .findFirst().orElse(null);
                m.put("saleOrderLineId", line == null ? null : line.getId());
                m.put("sku", line != null ? line.getProduct().getSku() : shopifyLine == null ? null : shopifyLine.sku());
                m.put("title", line != null ? line.getProduct().getName() : shopifyLine == null ? null : shopifyLine.title());
                m.put("quantity", rl.quantity());
                m.put("amount", rl.amount());
                m.put("restockType", rl.restockType());
                lines.add(m);
            }
            ShopifyRefund refund = new ShopifyRefund();
            refund.setSaleOrderId(so.getId());
            refund.setRefundId(r.id());
            refund.setRefundedAt(r.createdAt() == null ? LocalDateTime.now()
                    : r.createdAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
            refund.setAmount(r.amount());
            refund.setOtherAmount(r.otherAmount());
            refund.setNote(r.note());
            try {
                refund.setLines(objectMapper.writeValueAsString(lines));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            refundRepository.save(refund);
            auditService.log("SALE_ORDER", so.getId(), "SHOPIFY_REFUND", Map.of("shopifyOrder", String.valueOf(o.name()),
                    "refund", r.id(), "amount", r.amount(), "toReview", true));
            added = true;
        }
        return added;
    }

    /** Cancelled in Shopify before anything shipped: the ERP order is cancelled and its stock freed. */
    private boolean cancelUnshipped(SaleOrder so, ShopifyOrder o) {
        if (so.getStatus() != Status.CONFIRMED || so.hasFulfilledAnything()) {
            // Shipped already, or finished: handled later (business-rules.md §5)
            return false;
        }
        saleOrderService.cancel(so.getId());
        auditService.log("SALE_ORDER", so.getId(), "CANCELLED_IN_SHOPIFY", Map.of("shopifyOrder", String.valueOf(o.name()), "reason", "cancelled in Shopify"));
        return true;
    }

    private static BigDecimal erpTotal(SaleOrder so) {
        BigDecimal total = BigDecimal.ZERO;
        for (SaleOrderLine l : so.getLines()) {
            BigDecimal net = SaleAmounts.net(l.getQuantity(), l.getUnitPrice(), l.getDiscountPercent());
            total = total.add(net).add(SaleAmounts.tax(net, l.getTaxRate()));
        }
        for (SaleOrderCharge c : so.getCharges()) {
            BigDecimal net = SaleAmounts.net(c.getQuantity(), c.getUnitPrice(), c.getDiscountPercent());
            total = total.add(net).add(SaleAmounts.tax(net, c.getTaxRate()));
        }
        return total;
    }

    private static String money(BigDecimal v) {
        return "£" + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static Address address(ShopifyOrder.Address a) {
        return a == null ? null : Address.clean(new Address(a.address1(), a.address2(), a.city(), null, a.zip(), a.country()));
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
        Address delivery = address(ship);
        if (delivery != null) {
            CustomerAddress ca = new CustomerAddress();
            ca.setAddress(delivery);
            ca.setType(CustomerAddress.Type.SHIPPING);
            ca.setDefaultForType(true);
            c.replaceAddresses(new ArrayList<>(List.of(ca)));
        }
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
