package com.experterp.backend.service;

import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.SaleReturnRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.InvoiceRepository;
import com.experterp.backend.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * Credit notes &amp; restock (Cin7's C&amp;R, e.g. CR-00231 for INV-118520): a credit note is for one sales invoice
 * and credits some of its quantities (never more than invoiced and not credited yet) and / or extra amounts such
 * as a shipping refund, with the invoice's prices, discounts and VAT. Goods marked for restock come back into
 * stock through a return on the order. The credit is then settled: a refund (money paid back, with its
 * reference), paying off what is still due on the invoice, or kept as customer credit.
 * A credit note is issued at once and cannot be cancelled; the credited invoice can then no longer be cancelled.
 */
@Service
@RequiredArgsConstructor
public class CreditNoteService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final SaleOrderService saleOrderService;
    private final OrganisationSettingsService settingsService;
    private final TaxService taxService;
    private final AuditService auditService;

    @Transactional
    public InvoiceResponse create(Long invoiceId, CreditNoteRequest req) {
        Invoice inv = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
        if (inv.getType() != Invoice.InvoiceType.SALES || inv.getKind() != Invoice.Kind.INVOICE) {
            throw new IllegalStateException("Credit notes are made for sales invoices");
        }
        if (inv.getStatus() != Invoice.InvoiceStatus.ISSUED && inv.getStatus() != Invoice.InvoiceStatus.PAID) {
            throw new IllegalStateException("Only issued or paid invoices can be credited (" + inv.getInvoiceNumber() + " is " + inv.getStatus() + ")");
        }
        if (req.getLines().isEmpty() && req.getCharges().isEmpty()) {
            throw new IllegalArgumentException("Credit at least one item or amount");
        }

        Invoice cn = new Invoice();
        cn.setType(Invoice.InvoiceType.SALES);
        cn.setKind(Invoice.Kind.CREDIT_NOTE);
        cn.setCreditedInvoiceId(inv.getId());
        cn.setPartyName(inv.getPartyName());
        cn.setCustomerId(inv.getCustomerId());
        cn.setSaleOrderId(inv.getSaleOrderId());
        cn.setCurrency(inv.getCurrency());
        cn.setNotes(req.getReason());
        cn.setIssueDate(LocalDate.now());
        cn.setDueDate(LocalDate.now());
        cn.setStatus(Invoice.InvoiceStatus.ISSUED);

        Map<Long, InvoiceItem> itemsById = new HashMap<>();
        inv.getItems().forEach(i -> itemsById.put(i.getId(), i));
        Set<Long> seen = new HashSet<>();
        List<SaleReturnRequest.Line> restock = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO, tax = BigDecimal.ZERO;

        for (CreditNoteRequest.Line l : req.getLines()) {
            InvoiceItem item = itemsById.get(l.getInvoiceItemId());
            if (item == null) throw new ResourceNotFoundException("Item " + l.getInvoiceItemId() + " is not on " + inv.getInvoiceNumber());
            if (!seen.add(item.getId())) throw new IllegalArgumentException("Item " + item.getId() + " appears more than once");
            BigDecimal left = item.getQuantity().subtract(item.getCreditedQuantity());
            String what = item.getProduct() != null ? item.getProduct().getSku() : item.getDescription();
            if (l.getQuantity().compareTo(left) > 0) {
                throw new IllegalStateException(String.format("Cannot credit %s of %s: only %s invoiced and not credited yet",
                        plain(l.getQuantity()), what, plain(left)));
            }
            InvoiceItem c = item(cn, l.getQuantity(), item.getUnitPrice(), item.getDiscountPercent(), item.getTaxName(), item.getTaxRate());
            c.setProduct(item.getProduct());
            c.setDescription(item.getDescription());
            c.setCreditedItemId(item.getId());
            c.setSaleOrderLineId(item.getSaleOrderLineId());
            c.setSaleOrderChargeId(item.getSaleOrderChargeId());
            item.setCreditedQuantity(item.getCreditedQuantity().add(l.getQuantity()));
            net = net.add(c.getTotalPrice());
            tax = tax.add(c.getTaxAmount());

            if (l.isRestock()) {
                if (item.getSaleOrderLineId() == null) throw new IllegalStateException(what + " is not a product of the order: nothing to restock");
                SaleReturnRequest.Line r = new SaleReturnRequest.Line();
                r.setSaleOrderLineId(item.getSaleOrderLineId());
                r.setQuantity(l.getQuantity());
                r.setRestock(true);
                restock.add(r);
            }
        }

        Customer customer = inv.getCustomerId() == null ? null : customerRepository.findById(inv.getCustomerId()).orElse(null);
        for (CreditNoteRequest.Charge ch : req.getCharges()) {
            TaxRate rate = ch.getTaxRule() == null || ch.getTaxRule().isBlank()
                    ? taxService.resolveSale(null, customer) : taxService.saleRate(ch.getTaxRule());
            BigDecimal qty = ch.getQuantity() != null ? ch.getQuantity() : BigDecimal.ONE;
            InvoiceItem c = item(cn, qty, ch.getUnitPrice(), BigDecimal.ZERO, rate.getName(), rate.getRate());
            c.setDescription(ch.getDescription().trim());
            net = net.add(c.getTotalPrice());
            tax = tax.add(c.getTaxAmount());
        }

        cn.setSubtotal(net);
        cn.setTaxTotal(tax);
        cn.setTotalAmount(net.add(tax));
        cn.setInvoiceNumber(settingsService.nextCreditNoteNumber());
        invoiceRepository.save(cn);

        // Goods back into stock: a return on the order (checks they were shipped and not returned yet)
        if (!restock.isEmpty()) {
            if (inv.getSaleOrderId() == null) throw new IllegalStateException(inv.getInvoiceNumber() + " is not for an order: nothing to restock");
            SaleReturnRequest ret = new SaleReturnRequest();
            ret.setWarehouseId(req.getWarehouseId());
            ret.setReason("Credit note " + cn.getInvoiceNumber() + (req.getReason() == null ? "" : ": " + req.getReason()));
            ret.setLines(restock);
            saleOrderService.createReturn(inv.getSaleOrderId(), ret);
        }

        String settled = settle(inv, cn, req);
        auditService.log("INVOICE", cn.getId(), "CREDIT_NOTE", Map.of(
                "creditNote", cn.getInvoiceNumber(), "invoice", inv.getInvoiceNumber(), "total", cn.getTotalAmount(),
                "restocked", restock.size(), "settled", settled));
        return invoiceService.findById(cn.getId());
    }

    // Default: pay off what is still due on the invoice, keep any rest as customer credit
    private String settle(Invoice inv, Invoice cn, CreditNoteRequest req) {
        CreditNoteRequest.Settle how = req.getSettle() != null ? req.getSettle() : CreditNoteRequest.Settle.APPLY_TO_INVOICE;
        BigDecimal available = cn.getTotalAmount();
        switch (how) {
            case APPLY_TO_INVOICE -> {
                BigDecimal due = inv.getTotalAmount().subtract(paid(inv));
                BigDecimal apply = available.min(due);
                if (apply.signum() > 0) {
                    pay(inv, apply, Payment.PaymentMethod.CREDIT_NOTE, cn.getInvoiceNumber(), cn.getId());
                    if (paid(inv).compareTo(inv.getTotalAmount()) >= 0) inv.setStatus(Invoice.InvoiceStatus.PAID);
                    available = available.subtract(apply);
                }
            }
            case REFUND -> {
                if (req.getRefundMethod() == Payment.PaymentMethod.CREDIT_NOTE) {
                    throw new IllegalArgumentException("A refund is paid by cash, bank or card");
                }
                pay(cn, available, req.getRefundMethod(), req.getRefundReference(), null);
                available = BigDecimal.ZERO;
            }
            case KEEP_AS_CREDIT -> { }
        }
        if (available.signum() <= 0) cn.setStatus(Invoice.InvoiceStatus.PAID);
        return how.name();
    }

    private void pay(Invoice target, BigDecimal amount, Payment.PaymentMethod method, String reference, Long creditNoteId) {
        Payment p = new Payment();
        p.setInvoice(target);
        p.setAmount(amount);
        p.setMethod(method);
        p.setReference(reference);
        p.setCreditNoteId(creditNoteId);
        paymentRepository.save(p);
        target.getPayments().add(p);
    }

    private static BigDecimal paid(Invoice inv) {
        return inv.getPayments().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static InvoiceItem item(Invoice inv, BigDecimal qty, BigDecimal unitPrice, BigDecimal discount, String taxName, BigDecimal taxRate) {
        InvoiceItem item = new InvoiceItem();
        item.setInvoice(inv);
        item.setQuantity(qty);
        item.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
        item.setDiscountPercent(discount);
        item.setTaxName(taxName);
        item.setTaxRate(taxRate);
        BigDecimal net = SaleAmounts.net(qty, unitPrice, discount);
        item.setTotalPrice(net);
        item.setTaxAmount(SaleAmounts.tax(net, taxRate));
        inv.getItems().add(item);
        return item;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
