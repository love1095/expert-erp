package com.experterp.backend.integration.shopify;

import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.InvoiceRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import com.experterp.backend.repository.ShopifyRefundRepository;
import com.experterp.backend.service.AuditService;
import com.experterp.backend.service.CreditNoteService;
import com.experterp.backend.service.TaxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Refunds made in Shopify, reviewed on their ERP order (7.4; business-rules.md §5: never credited by themselves).
 * Applying one does what the ERP would do by hand: refunded items that were invoiced get a credit note on that
 * invoice (with the goods back in stock where the person ticks it), items not invoiced and not shipped are
 * cancelled on the order, and money given back beyond the items (shipping, goodwill) is credited as a charge.
 * The credit note is settled against what is still due, else recorded as paid back through Shopify.
 */
@Service
@RequiredArgsConstructor
public class ShopifyRefundService {

    private final ShopifyRefundRepository refundRepository;
    private final SaleOrderRepository saleOrderRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final CreditNoteService creditNoteService;
    private final TaxService taxService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public record Line(String lineItemId, Long saleOrderLineId, String sku, String title, BigDecimal quantity,
                       BigDecimal amount, String restockType) {}

    public record RefundView(Long id, String refundId, LocalDateTime refundedAt, BigDecimal amount, BigDecimal otherAmount,
                             String note, ShopifyRefund.Status status, String outcome, String reviewedBy,
                             LocalDateTime reviewedAt, List<Line> lines) {}

    /** A refund waiting somewhere, for the Shopify page. */
    public record Waiting(Long id, Long saleOrderId, String orderNumber, String shopifyOrder, BigDecimal amount, LocalDateTime refundedAt) {}

    /** restockLineIds: order lines whose refunded units came back and go into stock again. */
    public record ApplyRequest(List<Long> restockLineIds) {}

    @Transactional(readOnly = true)
    public List<RefundView> forOrder(Long saleOrderId) {
        return refundRepository.findBySaleOrderIdOrderByRefundedAtAscIdAsc(saleOrderId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public long toReview() {
        return refundRepository.countByStatus(ShopifyRefund.Status.TO_REVIEW);
    }

    @Transactional(readOnly = true)
    public List<Waiting> waiting() {
        List<ShopifyRefund> refunds = refundRepository.findByStatusOrderByRefundedAtAsc(ShopifyRefund.Status.TO_REVIEW);
        Map<Long, SaleOrder> orders = new HashMap<>();
        saleOrderRepository.findAllById(refunds.stream().map(ShopifyRefund::getSaleOrderId).toList()).forEach(o -> orders.put(o.getId(), o));
        return refunds.stream().map(r -> {
            SaleOrder o = orders.get(r.getSaleOrderId());
            return new Waiting(r.getId(), r.getSaleOrderId(), o == null ? null : o.getOrderNumber(),
                    o == null ? null : o.getExternalReference(), r.getAmount(), r.getRefundedAt());
        }).toList();
    }

    @Transactional
    public RefundView apply(Long saleOrderId, Long id, ApplyRequest req) {
        ShopifyRefund refund = toReview(saleOrderId, id);
        SaleOrder so = saleOrderRepository.findById(saleOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Sale order not found: " + saleOrderId));
        Set<Long> restock = new HashSet<>(req == null || req.restockLineIds() == null ? List.of() : req.restockLineIds());

        // Invoices that can be credited, newest first
        List<Invoice> invoices = new ArrayList<>(invoiceRepository.findBySaleOrderIdOrderByCreatedAtAscIdAsc(saleOrderId).stream()
                .filter(i -> i.getType() == Invoice.InvoiceType.SALES && i.getKind() == Invoice.Kind.INVOICE
                        && (i.getStatus() == Invoice.InvoiceStatus.ISSUED || i.getStatus() == Invoice.InvoiceStatus.PAID))
                .toList());
        Collections.reverse(invoices);
        Map<Long, CreditNoteRequest> credits = new LinkedHashMap<>();
        List<String> done = new ArrayList<>();
        List<String> notDone = new ArrayList<>();
        int restocked = 0;

        for (Line l : lines(refund)) {
            if (l.quantity() == null || l.quantity().signum() <= 0) continue;
            SaleOrderLine line = l.saleOrderLineId() == null ? null
                    : so.getLines().stream().filter(x -> x.getId().equals(l.saleOrderLineId())).findFirst().orElse(null);
            String what = l.sku() != null ? l.sku() : l.title() != null ? l.title() : "item";
            if (line == null) {
                notDone.add(plain(l.quantity()) + " × " + what + " is not on the ERP order");
                continue;
            }
            BigDecimal left = l.quantity();
            // Goods that can come back: shipped and not returned yet
            BigDecimal canReturn = line.getFulfilledQuantity().subtract(line.getReturnedQuantity());
            for (Invoice inv : invoices) {
                for (InvoiceItem item : inv.getItems()) {
                    if (left.signum() <= 0) break;
                    if (!line.getId().equals(item.getSaleOrderLineId())) continue;
                    BigDecimal take = left.min(item.getQuantity().subtract(item.getCreditedQuantity()));
                    if (take.signum() <= 0) continue;
                    CreditNoteRequest.Line cl = new CreditNoteRequest.Line();
                    cl.setInvoiceItemId(item.getId());
                    cl.setQuantity(take);
                    boolean back = restock.contains(line.getId()) && canReturn.compareTo(take) >= 0;
                    cl.setRestock(back);
                    if (back) {
                        canReturn = canReturn.subtract(take);
                        restocked++;
                    }
                    credits.computeIfAbsent(inv.getId(), k -> new CreditNoteRequest()).getLines().add(cl);
                    left = left.subtract(take);
                }
            }
            // Not invoiced: if it has not shipped either, it is simply not sold any more
            if (left.signum() > 0) {
                BigDecimal cancel = left.min(line.getRemainingQuantity());
                if (cancel.signum() > 0) {
                    line.setCancelledQuantity(line.getCancelledQuantity().add(cancel));
                    done.add(plain(cancel) + " × " + what + " cancelled on the order");
                    left = left.subtract(cancel);
                }
            }
            if (left.signum() > 0) notDone.add(plain(left) + " × " + what + " was neither invoiced nor open on the order");
        }

        // Money given back beyond the items: credited on the newest invoice
        if (refund.getOtherAmount().signum() > 0) {
            if (invoices.isEmpty()) {
                notDone.add("£" + refund.getOtherAmount() + " refunded beyond the items: there is no invoice to credit");
            } else {
                Invoice inv = invoices.get(0);
                Customer customer = inv.getCustomerId() == null ? null : customerRepository.findById(inv.getCustomerId()).orElse(null);
                TaxRate rate = taxService.resolveSale(null, customer);
                CreditNoteRequest.Charge ch = new CreditNoteRequest.Charge();
                ch.setDescription("Refunded in Shopify (shipping or other)");
                ch.setUnitPrice(VatIncluded.split(refund.getOtherAmount(), BigDecimal.ONE, rate.getRate()).unitNet());
                ch.setTaxRule(rate.getName());
                credits.computeIfAbsent(inv.getId(), k -> new CreditNoteRequest()).getCharges().add(ch);
            }
        }

        if (credits.isEmpty() && done.isEmpty()) {
            throw new IllegalStateException("Nothing to credit or cancel for this refund"
                    + (notDone.isEmpty() ? "" : " (" + String.join("; ", notDone) + ")") + ": dismiss it with a note instead");
        }

        // Everything left on the order is cancelled now: the order is finished
        if (!done.isEmpty() && so.getLines().stream().allMatch(x -> x.getRemainingQuantity().signum() <= 0)
                && (so.getStatus() == SaleOrder.Status.DRAFT || so.getStatus() == SaleOrder.Status.CONFIRMED)) {
            so.setStatus(so.hasFulfilledAnything() ? SaleOrder.Status.COMPLETED : SaleOrder.Status.CANCELLED);
            so.setCancelledAt(LocalDateTime.now());
        }

        for (Map.Entry<Long, CreditNoteRequest> e : credits.entrySet()) {
            Invoice inv = invoices.stream().filter(i -> i.getId().equals(e.getKey())).findFirst().orElseThrow();
            CreditNoteRequest cr = e.getValue();
            cr.setReason("Shopify refund " + refund.getRefundId() + (refund.getNote() == null ? "" : ": " + refund.getNote()));
            BigDecimal paid = inv.getPayments().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (inv.getTotalAmount().subtract(paid).signum() > 0) {
                cr.setSettle(CreditNoteRequest.Settle.APPLY_TO_INVOICE);
            } else {
                // Paid already: Shopify gave the money back
                cr.setSettle(CreditNoteRequest.Settle.REFUND);
                cr.setRefundMethod(Payment.PaymentMethod.SHOPIFY);
                cr.setRefundReference("Shopify refund " + refund.getRefundId());
            }
            InvoiceResponse cn = creditNoteService.create(inv.getId(), cr);
            done.add(0, "Credit note " + cn.getInvoiceNumber() + " for " + inv.getInvoiceNumber() + " (£" + cn.getTotalAmount() + ")");
        }
        if (restocked > 0) done.add(restocked + " line(s) back in stock");
        done.addAll(notDone.stream().map(n -> "not done: " + n).toList());

        refund.setStatus(ShopifyRefund.Status.DONE);
        refund.setOutcome(String.join("; ", done));
        refund.setReviewedBy(auditService.currentActorName());
        refund.setReviewedAt(LocalDateTime.now());
        auditService.log("SALE_ORDER", saleOrderId, "SHOPIFY_REFUND_APPLIED", Map.of("refund", refund.getRefundId(), "outcome", refund.getOutcome()));
        return view(refund);
    }

    @Transactional
    public RefundView dismiss(Long saleOrderId, Long id, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the refund needs nothing in the ERP");
        ShopifyRefund refund = toReview(saleOrderId, id);
        refund.setStatus(ShopifyRefund.Status.DISMISSED);
        refund.setOutcome("Dismissed: " + reason.trim());
        refund.setReviewedBy(auditService.currentActorName());
        refund.setReviewedAt(LocalDateTime.now());
        auditService.log("SALE_ORDER", saleOrderId, "SHOPIFY_REFUND_DISMISSED", Map.of("refund", refund.getRefundId(), "reason", reason.trim()));
        return view(refund);
    }

    private ShopifyRefund toReview(Long saleOrderId, Long id) {
        ShopifyRefund r = refundRepository.findById(id).filter(x -> x.getSaleOrderId().equals(saleOrderId))
                .orElseThrow(() -> new ResourceNotFoundException("Shopify refund not found on this order: " + id));
        if (r.getStatus() != ShopifyRefund.Status.TO_REVIEW) throw new IllegalStateException("This refund was reviewed already");
        return r;
    }

    private List<Line> lines(ShopifyRefund r) {
        try {
            return objectMapper.readValue(r.getLines(), new TypeReference<List<Line>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Refund " + r.getRefundId() + " has unreadable lines");
        }
    }

    private RefundView view(ShopifyRefund r) {
        return new RefundView(r.getId(), r.getRefundId(), r.getRefundedAt(), r.getAmount(), r.getOtherAmount(), r.getNote(),
                r.getStatus(), r.getOutcome(), r.getReviewedBy(), r.getReviewedAt(), lines(r));
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
