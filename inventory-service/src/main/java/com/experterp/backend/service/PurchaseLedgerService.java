package com.experterp.backend.service;

import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.entity.StockMovement.MovementType;
import com.experterp.backend.entity.StockMovement.ReferenceType;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The purchase ledger (phase H2, spec §6), like Cin7's purchase Payments and Credit notes: supplier credit notes
 * against a bill (optionally sending the goods back, at the cost they came in at), supplier credit (deposits and
 * overpaid bills, "Use supplier deposit"), allocating a kept credit note to another bill, what each supplier is owed,
 * and aged creditors.
 */
@Service
@RequiredArgsConstructor
public class PurchaseLedgerService {

    private static final BigDecimal PENNY = new BigDecimal("0.01");

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final SupplierCreditRepository creditRepository;
    private final SupplierRepository supplierRepository;
    private final PaymentAccountRepository accountRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final StockMovementRepository movementRepository;
    private final StockMovementService movementService;
    private final InvoiceService invoiceService;
    private final TaxService taxService;
    private final AuditService auditService;
    private final LedgerPeriodService periodService;
    private final ReconciliationGuard reconciliationGuard;
    private final JdbcTemplate jdbc;

    // ---- supplier credit notes ----

    public enum Settle { APPLY_TO_BILL, REFUND, KEEP_AS_CREDIT }

    /** A line of the bill credited; {@code returnGoods}: the goods go back to the supplier (out of stock). */
    public record CreditLine(Long invoiceItemId, BigDecimal quantity, boolean returnGoods) {}

    /** An amount credited that is not a bill line (e.g. a rebate, a shipping refund). */
    public record CreditCharge(String description, BigDecimal quantity, BigDecimal unitPrice, String taxRule) {}

    public record CreditNoteRequest(String number, LocalDate date, List<CreditLine> lines, List<CreditCharge> charges, String reason,
                                    Settle settle, Long refundAccountId, String refundReference) {}

    @Transactional
    public InvoiceResponse createCreditNote(Long billId, CreditNoteRequest req) {
        Invoice bill = invoiceRepository.findById(billId).orElseThrow(() -> new ResourceNotFoundException("Bill not found: " + billId));
        if (bill.getType() != Invoice.InvoiceType.PURCHASE || bill.getKind() != Invoice.Kind.INVOICE) {
            throw new IllegalStateException("Supplier credit notes are made for supplier bills");
        }
        if (bill.getStatus() != Invoice.InvoiceStatus.ISSUED && bill.getStatus() != Invoice.InvoiceStatus.PAID) {
            throw new IllegalStateException("Only issued or paid bills can be credited (" + bill.getInvoiceNumber() + " is " + bill.getStatus() + ")");
        }
        List<CreditLine> lines = req.lines() == null ? List.of() : req.lines();
        List<CreditCharge> charges = req.charges() == null ? List.of() : req.charges();
        if (lines.isEmpty() && charges.isEmpty()) throw new IllegalArgumentException("Credit at least one line or amount");
        LocalDate date = req.date() != null ? req.date() : LocalDate.now();
        periodService.check(date, "a supplier credit note");
        if (date.isAfter(LocalDate.now())) throw new IllegalArgumentException("A credit note cannot be dated in the future");

        Invoice cn = new Invoice();
        cn.setType(Invoice.InvoiceType.PURCHASE);
        cn.setKind(Invoice.Kind.CREDIT_NOTE);
        cn.setCreditedInvoiceId(bill.getId());
        cn.setPartyName(bill.getPartyName());
        cn.setSupplierId(bill.getSupplierId());
        cn.setPurchaseOrderId(bill.getPurchaseOrderId());
        cn.setCurrency(bill.getCurrency());
        cn.setNotes(blank(req.reason()));
        cn.setIssueDate(date);
        cn.setDueDate(date);
        cn.setStatus(Invoice.InvoiceStatus.ISSUED);
        // The supplier's own credit note number, else one made from the bill's
        String number = blank(req.number());
        if (number == null) {
            long n = invoiceRepository.countByCreditedInvoiceId(bill.getId()) + 1;
            number = bill.getInvoiceNumber() + "-CR" + n;
        }
        cn.setInvoiceNumber(number);

        Map<Long, InvoiceItem> items = new HashMap<>();
        bill.getItems().forEach(i -> items.put(i.getId(), i));
        Set<Long> seen = new HashSet<>();
        BigDecimal net = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        record Back(InvoiceItem item, BigDecimal qty) {}
        List<Back> back = new ArrayList<>();
        for (CreditLine l : lines) {
            InvoiceItem item = items.get(l.invoiceItemId());
            if (item == null) throw new ResourceNotFoundException("Line " + l.invoiceItemId() + " is not on " + bill.getInvoiceNumber());
            if (!seen.add(item.getId())) throw new IllegalArgumentException("Line " + item.getId() + " appears more than once");
            if (l.quantity() == null || l.quantity().signum() <= 0) throw new IllegalArgumentException("Give a quantity to credit");
            BigDecimal left = item.getQuantity().subtract(item.getCreditedQuantity());
            String what = item.getProduct() != null ? item.getProduct().getSku() : item.getDescription();
            if (l.quantity().compareTo(left) > 0) {
                throw new IllegalStateException("Cannot credit " + plain(l.quantity()) + " of " + what + ": only " + plain(left) + " billed and not credited yet");
            }
            InvoiceItem c = item(cn, l.quantity(), item.getUnitPrice(), item.getDiscountPercent(), item.getTaxName(), item.getTaxRate());
            c.setProduct(item.getProduct());
            c.setDescription(item.getDescription());
            c.setCreditedItemId(item.getId());
            c.setPurchaseOrderLineId(item.getPurchaseOrderLineId());
            item.setCreditedQuantity(item.getCreditedQuantity().add(l.quantity()));
            net = net.add(c.getTotalPrice());
            tax = tax.add(c.getTaxAmount());
            if (l.returnGoods()) {
                if (item.getProduct() == null || item.getPurchaseOrderLineId() == null) {
                    throw new IllegalStateException(what + " is not a product received on the order: nothing to send back");
                }
                back.add(new Back(item, l.quantity()));
            }
        }
        Supplier supplier = bill.getSupplierId() == null ? null : supplierRepository.findById(bill.getSupplierId()).orElse(null);
        for (CreditCharge ch : charges) {
            if (ch.description() == null || ch.description().isBlank()) throw new IllegalArgumentException("Say what the amount is for");
            if (ch.unitPrice() == null || ch.unitPrice().signum() <= 0) throw new IllegalArgumentException("Give the amount credited");
            TaxRate rate = ch.taxRule() == null || ch.taxRule().isBlank() ? taxService.resolvePurchase(null, supplier)
                    : taxService.rate(TaxRate.Usage.PURCHASE, ch.taxRule());
            BigDecimal qty = ch.quantity() != null ? ch.quantity() : BigDecimal.ONE;
            InvoiceItem c = item(cn, qty, ch.unitPrice(), BigDecimal.ZERO, rate.getName(), rate.getRate());
            c.setDescription(ch.description().trim());
            net = net.add(c.getTotalPrice());
            tax = tax.add(c.getTaxAmount());
        }
        cn.setSubtotal(net);
        cn.setTaxTotal(tax);
        cn.setTotalAmount(net.add(tax));
        invoiceRepository.save(cn);

        // Goods sent back: out of the order's warehouse, off the layers that receipt made (CostingService)
        if (!back.isEmpty()) {
            PurchaseOrder po = bill.getPurchaseOrderId() == null ? null : purchaseOrderRepository.findById(bill.getPurchaseOrderId()).orElse(null);
            if (po == null || po.getWarehouse() == null) throw new IllegalStateException(bill.getInvoiceNumber() + " is not for an order: nothing to send back");
            for (Back b : back) {
                Long lineId = b.item().getPurchaseOrderLineId();
                BigDecimal received = received(lineId), returned = returned(lineId);
                if (b.qty().compareTo(received.subtract(returned)) > 0) {
                    throw new IllegalStateException("Cannot send back " + plain(b.qty()) + " of " + b.item().getProduct().getSku() + ": "
                            + plain(received) + " received, " + plain(returned) + " already sent back");
                }
                movementService.createMovement(b.item().getProduct(), po.getWarehouse(), b.qty(), MovementType.PURCHASE_RETURN_OUT,
                        ReferenceType.PURCHASE_RETURN, cn.getInvoiceNumber(), "Returned to " + bill.getPartyName() + " on credit note "
                                + cn.getInvoiceNumber(), lineId, null);
            }
        }

        String settled = settle(bill, cn, req);
        auditService.log("INVOICE", cn.getId(), "SUPPLIER_CREDIT_NOTE", Map.of("creditNote", cn.getInvoiceNumber(), "bill", bill.getInvoiceNumber(),
                "total", cn.getTotalAmount(), "returned", back.size(), "settled", settled));
        return invoiceService.findById(cn.getId());
    }

    // Default: pay off what is still due on the bill, keep any rest as credit with the supplier
    private String settle(Invoice bill, Invoice cn, CreditNoteRequest req) {
        Settle how = req.settle() != null ? req.settle() : Settle.APPLY_TO_BILL;
        BigDecimal available = cn.getTotalAmount();
        switch (how) {
            case APPLY_TO_BILL -> {
                BigDecimal due = bill.getTotalAmount().subtract(invoiceService.settled(bill));
                BigDecimal apply = available.min(due);
                if (apply.signum() > 0 && bill.getStatus() == Invoice.InvoiceStatus.ISSUED) {
                    pay(bill, apply, Payment.PaymentMethod.CREDIT_NOTE, cn.getInvoiceNumber(), cn.getId(), null, null);
                    if (invoiceService.settled(bill).compareTo(bill.getTotalAmount()) >= 0) bill.setStatus(Invoice.InvoiceStatus.PAID);
                    available = available.subtract(apply);
                }
            }
            case REFUND -> {
                // The supplier paid the money back: received into a payment account
                PaymentAccount account = invoiceService.paymentAccount(req.refundAccountId());
                Payment p = pay(cn, available, account != null ? account.getKind().method() : Payment.PaymentMethod.BANK,
                        blank(req.refundReference()), null, null, account != null ? account.getId() : null);
                p.setPaidAt(cn.getIssueDate().equals(LocalDate.now()) ? java.time.LocalDateTime.now() : cn.getIssueDate().atTime(12, 0));
                available = BigDecimal.ZERO;
            }
            case KEEP_AS_CREDIT -> { }
        }
        if (available.signum() <= 0) cn.setStatus(Invoice.InvoiceStatus.PAID);
        return how.name();
    }

    private BigDecimal received(Long purchaseOrderLineId) {
        return movementRepository.sumBySourceLine(MovementType.PURCHASE_IN, purchaseOrderLineId);
    }

    private BigDecimal returned(Long purchaseOrderLineId) {
        return movementRepository.sumBySourceLine(MovementType.PURCHASE_RETURN_OUT, purchaseOrderLineId);
    }

    // ---- supplier credit (deposits) ----

    public record CreditView(Long id, LocalDate paidOn, BigDecimal amount, BigDecimal used, BigDecimal available, Payment.PaymentMethod method,
                             String account, String reference, Long sourceInvoiceId, String sourceInvoiceNumber, Long purchaseOrderId,
                             String note, String createdBy) {}

    public record DepositRequest(BigDecimal amount, LocalDate paidOn, Long accountId, String reference, Long purchaseOrderId, String note) {}

    @Transactional(readOnly = true)
    public List<CreditView> credits(Long supplierId) {
        supplier(supplierId);
        return creditRepository.findBySupplierIdOrderByPaidOnAscIdAsc(supplierId).stream().map(this::view).toList();
    }

    /** Money paid to a supplier before the bill (a deposit, e.g. a proforma): the supplier's credit. */
    @Transactional
    public CreditView payDeposit(Long supplierId, DepositRequest req) {
        Supplier s = supplier(supplierId);
        if (req.amount() == null || req.amount().compareTo(PENNY) < 0) throw new IllegalArgumentException("Give the amount paid");
        LocalDate day = req.paidOn() != null ? req.paidOn() : LocalDate.now();
        periodService.check(day, "a supplier deposit");
        if (day.isAfter(LocalDate.now())) throw new IllegalArgumentException("A payment cannot be dated in the future");
        PaymentAccount account = invoiceService.paymentAccount(req.accountId());
        if (req.purchaseOrderId() != null) {
            PurchaseOrder po = purchaseOrderRepository.findById(req.purchaseOrderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase order not found: " + req.purchaseOrderId()));
            if (po.getSupplier() == null || !po.getSupplier().getId().equals(s.getId())) {
                throw new IllegalArgumentException(po.getOrderNumber() + " is another supplier's order");
            }
        }
        SupplierCredit c = new SupplierCredit();
        c.setSupplierId(s.getId());
        c.setAmount(req.amount().setScale(2, RoundingMode.HALF_UP));
        c.setPaidOn(day);
        c.setMethod(account != null ? account.getKind().method() : Payment.PaymentMethod.BANK);
        c.setPaymentAccountId(account != null ? account.getId() : null);
        c.setReference(blank(req.reference()));
        c.setPurchaseOrderId(req.purchaseOrderId());
        c.setNote(blank(req.note()) != null ? blank(req.note()) : "Deposit");
        c.setCreatedBy(auditService.currentActorName());
        creditRepository.save(c);
        auditService.log("SUPPLIER", s.getId(), "SUPPLIER_DEPOSIT", Map.of("amount", c.getAmount(), "paidOn", day.toString()));
        return view(c);
    }

    /** Pays a bill with the supplier's credit, oldest first (Cin7's Use supplier deposit). */
    @Transactional
    public InvoiceResponse useSupplierCredit(Long billId, BigDecimal amount) {
        Invoice bill = openBill(billId);
        BigDecimal due = bill.getTotalAmount().subtract(invoiceService.settled(bill));
        BigDecimal want = amount == null ? due : amount.min(due);
        if (want.compareTo(PENNY) < 0) throw new IllegalArgumentException("Give the amount to use");
        BigDecimal applied = BigDecimal.ZERO;
        for (SupplierCredit c : creditRepository.findBySupplierIdOrderByPaidOnAscIdAsc(bill.getSupplierId())) {
            BigDecimal left = c.getAmount().subtract(paymentRepository.sumUsedFromSupplierCredit(c.getId()));
            if (left.signum() <= 0) continue;
            BigDecimal take = left.min(want.subtract(applied));
            pay(bill, take, Payment.PaymentMethod.SUPPLIER_CREDIT, c.getReference(), null, c.getId(), null);
            applied = applied.add(take);
            if (applied.compareTo(want) >= 0) break;
        }
        if (applied.signum() == 0) throw new IllegalStateException("No deposit or credit with this supplier to use");
        if (invoiceService.settled(bill).compareTo(bill.getTotalAmount()) >= 0) bill.setStatus(Invoice.InvoiceStatus.PAID);
        auditService.log("INVOICE", bill.getId(), "SUPPLIER_CREDIT_USED", Map.of("bill", bill.getInvoiceNumber(), "amount", applied));
        return invoiceService.findById(bill.getId());
    }

    /** Pays a bill with a credit note of the same supplier that was kept as credit. */
    @Transactional
    public InvoiceResponse allocateCreditNote(Long billId, Long creditNoteId, BigDecimal amount) {
        Invoice bill = openBill(billId);
        Invoice cn = invoiceRepository.findById(creditNoteId).orElseThrow(() -> new ResourceNotFoundException("Credit note not found: " + creditNoteId));
        if (cn.getKind() != Invoice.Kind.CREDIT_NOTE || cn.getType() != Invoice.InvoiceType.PURCHASE || cn.getStatus() != Invoice.InvoiceStatus.ISSUED) {
            throw new IllegalStateException(cn.getInvoiceNumber() + " is not a supplier credit note with credit left");
        }
        if (!Objects.equals(cn.getSupplierId(), bill.getSupplierId())) throw new IllegalArgumentException(cn.getInvoiceNumber() + " is another supplier's credit note");
        BigDecimal take = cn.getTotalAmount().subtract(invoiceService.settled(cn)).min(bill.getTotalAmount().subtract(invoiceService.settled(bill)));
        if (amount != null) take = take.min(amount);
        if (take.compareTo(PENNY) < 0) throw new IllegalStateException(cn.getInvoiceNumber() + " has no credit left");
        pay(bill, take, Payment.PaymentMethod.CREDIT_NOTE, cn.getInvoiceNumber(), cn.getId(), null, null);
        if (invoiceService.settled(bill).compareTo(bill.getTotalAmount()) >= 0) bill.setStatus(Invoice.InvoiceStatus.PAID);
        if (invoiceService.settled(cn).compareTo(cn.getTotalAmount()) >= 0) cn.setStatus(Invoice.InvoiceStatus.PAID);
        auditService.log("INVOICE", bill.getId(), "CREDIT_NOTE_ALLOCATED", Map.of("bill", bill.getInvoiceNumber(), "creditNote", cn.getInvoiceNumber(), "amount", take));
        return invoiceService.findById(bill.getId());
    }

    /** Removes a deposit entered by mistake (manager or admin), only while none of it is used. */
    @Transactional
    public void voidCredit(Long supplierId, Long creditId, String reason) {
        SalesLedgerService.requireManager();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the deposit is removed");
        reconciliationGuard.requireNotReconciled("SC:" + creditId);
        SupplierCredit c = creditRepository.findById(creditId).filter(x -> x.getSupplierId().equals(supplierId))
                .orElseThrow(() -> new ResourceNotFoundException("Supplier credit not found: " + creditId));
        if (paymentRepository.sumUsedFromSupplierCredit(c.getId()).signum() > 0) {
            throw new IllegalStateException("Some of this deposit is used on bills: void those payments first");
        }
        creditRepository.delete(c);
        auditService.log("SUPPLIER", supplierId, "SUPPLIER_DEPOSIT_VOID", Map.of("amount", c.getAmount(), "reason", reason.trim()));
    }

    // ---- what suppliers are owed ----

    /** A bill not fully paid, or credit with the supplier not used, on a day. Credit is negative in {@code open}. */
    public record OpenItem(Long invoiceId, String kind, String number, LocalDate date, LocalDate dueDate, BigDecimal total, BigDecimal open, int daysOverdue) {}

    public record OpenCreditNote(Long id, String number, LocalDate date, BigDecimal available) {}

    /** What is open with a supplier on {@code on}: bills less what settled them by then, unused credit notes and deposits. */
    @Transactional(readOnly = true)
    public List<OpenItem> openItems(Long supplierId, LocalDate on) {
        Long t = TenantContext.get();
        Date d = Date.valueOf(on);
        List<OpenItem> items = new ArrayList<>(jdbc.query("""
                SELECT i.id, i.kind, i.invoice_number, COALESCE(i.issue_date, CAST(i.created_at AS date)) AS d, i.due_date, i.total_amount,
                       COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.invoice_id = i.id AND CAST(p.paid_at AS date) <= ?), 0)
                       + CASE WHEN i.kind = 'CREDIT_NOTE' THEN COALESCE((SELECT SUM(p.amount) FROM payments p
                              WHERE p.credit_note_id = i.id AND p.method = 'CREDIT_NOTE' AND CAST(p.paid_at AS date) <= ?), 0) ELSE 0 END AS settled
                FROM invoices i
                WHERE i.tenant_id = ? AND i.supplier_id = ? AND i.type = 'PURCHASE' AND i.status IN ('ISSUED', 'PAID')
                  AND COALESCE(i.issue_date, CAST(i.created_at AS date)) <= ?
                ORDER BY COALESCE(i.due_date, i.issue_date), i.id""", (rs, n) -> {
            boolean credit = "CREDIT_NOTE".equals(rs.getString("kind"));
            BigDecimal total = money(rs.getBigDecimal("total_amount"));
            BigDecimal open = total.subtract(money(rs.getBigDecimal("settled")));
            LocalDate issued = rs.getDate("d").toLocalDate();
            LocalDate due = rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate();
            int days = credit ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(due != null ? due : issued, on));
            return new OpenItem(rs.getLong("id"), rs.getString("kind"), rs.getString("invoice_number"), issued, due,
                    credit ? total.negate() : total, credit ? open.negate() : open, days);
        }, d, d, t, supplierId, d));
        items.addAll(jdbc.query("""
                SELECT sc.id, sc.paid_on, sc.reference, sc.amount,
                       COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.supplier_credit_id = sc.id AND CAST(p.paid_at AS date) <= ?), 0) AS used
                FROM supplier_credits sc WHERE sc.tenant_id = ? AND sc.supplier_id = ? AND sc.paid_on <= ?
                ORDER BY sc.paid_on, sc.id""", (rs, n) -> {
            BigDecimal amount = money(rs.getBigDecimal("amount"));
            String ref = rs.getString("reference");
            return new OpenItem(null, "DEPOSIT", ref != null ? "Deposit " + ref : "Deposit", rs.getDate("paid_on").toLocalDate(), null,
                    amount.negate(), money(rs.getBigDecimal("used")).subtract(amount), 0);
        }, d, t, supplierId, d));
        return items.stream().filter(i -> i.open().abs().compareTo(PENNY) >= 0).toList();
    }

    @Transactional(readOnly = true)
    public List<OpenCreditNote> openCreditNotes(Long supplierId) {
        supplier(supplierId);
        return openItems(supplierId, LocalDate.now()).stream().filter(i -> "CREDIT_NOTE".equals(i.kind()))
                .map(i -> new OpenCreditNote(i.invoiceId(), i.number(), i.date(), i.open().negate())).toList();
    }

    public record AgedCreditor(Long supplierId, String supplierName, BigDecimal current, BigDecimal days1to30, BigDecimal days31to60,
                               BigDecimal days61to90, BigDecimal over90, BigDecimal credit, BigDecimal total) {}

    public record AgedCreditors(LocalDate asAt, List<AgedCreditor> suppliers, AgedCreditor totals) {}

    /** What the organisation owes each supplier on a day, aged by days past the bills' due dates, most owed first. */
    @Transactional(readOnly = true)
    public AgedCreditors agedCreditors(LocalDate asAt) {
        LocalDate day = asAt == null || asAt.isAfter(LocalDate.now()) ? LocalDate.now() : asAt;
        List<AgedCreditor> rows = new ArrayList<>();
        for (Supplier s : supplierRepository.findAll()) {
            List<OpenItem> open = openItems(s.getId(), day);
            if (open.isEmpty()) continue;
            BigDecimal[] b = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
            BigDecimal credit = BigDecimal.ZERO;
            for (OpenItem i : open) {
                if (i.open().signum() < 0) { credit = credit.add(i.open()); continue; }
                int k = i.daysOverdue() <= 0 ? 0 : i.daysOverdue() <= 30 ? 1 : i.daysOverdue() <= 60 ? 2 : i.daysOverdue() <= 90 ? 3 : 4;
                b[k] = b[k].add(i.open());
            }
            BigDecimal total = Arrays.stream(b).reduce(BigDecimal.ZERO, BigDecimal::add).add(credit);
            rows.add(new AgedCreditor(s.getId(), s.getName(), money(b[0]), money(b[1]), money(b[2]), money(b[3]), money(b[4]), money(credit), money(total)));
        }
        rows.sort(Comparator.comparing(AgedCreditor::total).reversed().thenComparing(AgedCreditor::supplierName));
        AgedCreditor totals = new AgedCreditor(null, "Total", sum(rows, AgedCreditor::current), sum(rows, AgedCreditor::days1to30),
                sum(rows, AgedCreditor::days31to60), sum(rows, AgedCreditor::days61to90), sum(rows, AgedCreditor::over90),
                sum(rows, AgedCreditor::credit), sum(rows, AgedCreditor::total));
        return new AgedCreditors(day, rows, totals);
    }

    /** What the organisation owes the supplier today (bills less payments, credit notes and deposits). */
    @Transactional(readOnly = true)
    public BigDecimal balance(Long supplierId) {
        return money(openItems(supplierId, LocalDate.now()).stream().map(OpenItem::open).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    // ---- shared ----

    private static BigDecimal sum(List<AgedCreditor> rows, java.util.function.Function<AgedCreditor, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    private Invoice openBill(Long id) {
        Invoice bill = invoiceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Bill not found: " + id));
        if (bill.getType() != Invoice.InvoiceType.PURCHASE || bill.getKind() != Invoice.Kind.INVOICE) {
            throw new IllegalStateException(bill.getInvoiceNumber() + " is not a supplier bill");
        }
        if (bill.getStatus() != Invoice.InvoiceStatus.ISSUED) {
            throw new IllegalStateException(bill.getInvoiceNumber() + " is " + bill.getStatus().name().toLowerCase(Locale.ROOT) + ": nothing to pay");
        }
        if (bill.getSupplierId() == null) throw new IllegalStateException(bill.getInvoiceNumber() + " has no supplier");
        return bill;
    }

    private Payment pay(Invoice target, BigDecimal amount, Payment.PaymentMethod method, String reference, Long creditNoteId,
                        Long supplierCreditId, Long accountId) {
        Payment p = new Payment();
        p.setInvoice(target);
        p.setAmount(amount);
        p.setMethod(method);
        p.setReference(reference);
        p.setCreditNoteId(creditNoteId);
        p.setSupplierCreditId(supplierCreditId);
        p.setPaymentAccountId(accountId);
        paymentRepository.save(p);
        target.getPayments().add(p);
        paymentRepository.flush();
        return p;
    }

    private static InvoiceItem item(Invoice inv, BigDecimal qty, BigDecimal unitPrice, BigDecimal discount, String taxName, BigDecimal taxRate) {
        InvoiceItem item = new InvoiceItem();
        item.setInvoice(inv);
        item.setQuantity(qty);
        item.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
        item.setDiscountPercent(discount == null ? BigDecimal.ZERO : discount);
        item.setTaxName(taxName);
        item.setTaxRate(taxRate == null ? BigDecimal.ZERO : taxRate);
        // H3: a 100 % (Full VAT) line is all VAT
        BigDecimal[] split = SaleAmounts.split(qty, unitPrice, discount, taxRate);
        item.setTotalPrice(split[0]);
        item.setTaxAmount(split[1]);
        inv.getItems().add(item);
        return item;
    }

    private CreditView view(SupplierCredit c) {
        BigDecimal used = paymentRepository.sumUsedFromSupplierCredit(c.getId());
        String account = c.getPaymentAccountId() == null ? null : accountRepository.findById(c.getPaymentAccountId()).map(PaymentAccount::label).orElse(null);
        String source = c.getSourceInvoiceId() == null ? null : invoiceRepository.findById(c.getSourceInvoiceId()).map(Invoice::getInvoiceNumber).orElse(null);
        return new CreditView(c.getId(), c.getPaidOn(), c.getAmount(), used, c.getAmount().subtract(used), c.getMethod(), account,
                c.getReference(), c.getSourceInvoiceId(), source, c.getPurchaseOrderId(), c.getNote(), c.getCreatedBy());
    }

    private Supplier supplier(Long id) {
        return supplierRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Supplier not found: " + id));
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO.setScale(2) : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
