package com.experterp.backend.service;

import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * The sales ledger's money (phase H1, spec §6), like Cin7's invoice Payments section: payment accounts, customer
 * credit (overpayments and payments before the invoice) and using it, allocating a kept credit note to another
 * invoice, voiding a payment entered by mistake, and the aged debtors report.
 */
@Service
@RequiredArgsConstructor
public class SalesLedgerService {

    private static final BigDecimal PENNY = new BigDecimal("0.01");

    private final PaymentAccountRepository accountRepository;
    private final CustomerCreditRepository creditRepository;
    private final SupplierCreditRepository supplierCreditRepository;
    private final PaymentRepository paymentRepository;
    private final VoidedPaymentRepository voidedRepository;
    private final InvoiceRepository invoiceRepository;
    private final CustomerRepository customerRepository;
    private final InvoiceService invoiceService;
    private final StatementService statementService;
    private final AuditService auditService;

    // ---- payment accounts (Settings) ----

    public record AccountView(Long id, String code, String name, PaymentAccount.Kind kind, boolean active, boolean defaultAccount, boolean used) {}

    public record AccountRequest(Long id, String code, String name, PaymentAccount.Kind kind, boolean active, boolean defaultAccount) {}

    @Transactional(readOnly = true)
    public List<AccountView> accounts() {
        return accountRepository.findAllByOrderByCodeAsc().stream().map(this::view).toList();
    }

    /**
     * Saves the organisation's payment accounts as a whole: rows with an id are changed, rows without are added, rows
     * left out are removed, unless payments use them (then they are kept, no longer active). One default.
     */
    @Transactional
    public List<AccountView> saveAccounts(List<AccountRequest> rows) {
        Map<Long, PaymentAccount> existing = new HashMap<>();
        accountRepository.findAll().forEach(a -> existing.put(a.getId(), a));
        Set<String> codes = new HashSet<>();
        List<PaymentAccount> kept = new ArrayList<>();
        for (AccountRequest r : rows) {
            String code = r.code() == null ? "" : r.code().trim();
            String name = r.name() == null ? "" : r.name().trim();
            if (code.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("Each payment account needs a code and a name");
            if (code.length() > 20 || name.length() > 150) throw new IllegalArgumentException("Code or name too long: " + code);
            if (!codes.add(code.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Two payment accounts have the code " + code);
            PaymentAccount a = r.id() == null ? new PaymentAccount() : existing.remove(r.id());
            if (a == null) throw new ResourceNotFoundException("Payment account not found: " + r.id());
            a.setCode(code);
            a.setName(name);
            a.setKind(r.kind() != null ? r.kind() : PaymentAccount.Kind.BANK);
            a.setActive(r.active());
            a.setDefaultAccount(r.defaultAccount() && r.active());
            kept.add(a);
        }
        // Removing a used account would lose where its payments went: it stays, switched off
        for (PaymentAccount gone : existing.values()) {
            if (used(gone.getId())) {
                gone.setActive(false);
                gone.setDefaultAccount(false);
                kept.add(gone);
            } else {
                accountRepository.delete(gone);
            }
        }
        boolean seen = false;
        for (PaymentAccount a : kept) {
            if (a.isDefaultAccount() && !seen) seen = true;
            else a.setDefaultAccount(false);
        }
        accountRepository.flush();
        accountRepository.saveAll(kept);
        auditService.log("SETTINGS", null, "PAYMENT_ACCOUNTS", Map.of("accounts", kept.stream().map(PaymentAccount::label).toList()));
        return accounts();
    }

    private boolean used(Long accountId) {
        return paymentRepository.existsByPaymentAccountId(accountId) || creditRepository.existsByPaymentAccountId(accountId)
                || voidedRepository.existsByPaymentAccountId(accountId) || supplierCreditRepository.existsByPaymentAccountId(accountId);
    }

    private AccountView view(PaymentAccount a) {
        return new AccountView(a.getId(), a.getCode(), a.getName(), a.getKind(), a.isActive(), a.isDefaultAccount(), used(a.getId()));
    }

    // ---- customer credit ----

    public record CreditView(Long id, LocalDate receivedOn, BigDecimal amount, BigDecimal used, BigDecimal available,
                             Payment.PaymentMethod method, String account, String reference, Long sourceInvoiceId,
                             String sourceInvoiceNumber, String note, String createdBy) {}

    public record CreditRequest(BigDecimal amount, LocalDate receivedOn, Long accountId, Payment.PaymentMethod method, String reference, String note) {}

    @Transactional(readOnly = true)
    public List<CreditView> credits(Long customerId) {
        customer(customerId);
        return creditRepository.findByCustomerIdOrderByReceivedOnAscIdAsc(customerId).stream().map(this::view).toList();
    }

    /** Money received before there is an invoice for it (a prepayment): the customer's credit. */
    @Transactional
    public CreditView receiveOnAccount(Long customerId, CreditRequest req) {
        Customer c = customer(customerId);
        if (req.amount() == null || req.amount().compareTo(PENNY) < 0) throw new IllegalArgumentException("Give the amount received");
        LocalDate day = req.receivedOn() != null ? req.receivedOn() : LocalDate.now();
        if (day.isAfter(LocalDate.now())) throw new IllegalArgumentException("A payment cannot be dated in the future");
        PaymentAccount account = invoiceService.paymentAccount(req.accountId());
        Payment.PaymentMethod method = req.method() != null ? req.method() : account != null ? account.getKind().method() : null;
        if (method == null || method.isCredit()) throw new IllegalArgumentException("Choose the payment account (or how it was paid)");
        CustomerCredit credit = new CustomerCredit();
        credit.setCustomerId(c.getId());
        credit.setAmount(req.amount().setScale(2, java.math.RoundingMode.HALF_UP));
        credit.setReceivedOn(day);
        credit.setMethod(method);
        credit.setPaymentAccountId(account != null ? account.getId() : null);
        credit.setReference(blank(req.reference()));
        credit.setNote(blank(req.note()) != null ? blank(req.note()) : "Paid on account");
        credit.setCreatedBy(auditService.currentActorName());
        creditRepository.save(credit);
        auditService.log("CUSTOMER", c.getId(), "PAYMENT_ON_ACCOUNT", Map.of("amount", credit.getAmount(), "receivedOn", day.toString()));
        return view(credit);
    }

    /** Pays an invoice with the customer's credit, oldest first, up to what is due (or {@code amount}). */
    @Transactional
    public InvoiceResponse useCustomerCredit(Long invoiceId, BigDecimal amount) {
        Invoice inv = openSalesInvoice(invoiceId);
        BigDecimal due = due(inv);
        BigDecimal want = amount == null ? due : amount.min(due);
        if (want.compareTo(PENNY) < 0) throw new IllegalArgumentException("Give the amount to use");
        BigDecimal applied = BigDecimal.ZERO;
        for (CustomerCredit c : creditRepository.findByCustomerIdOrderByReceivedOnAscIdAsc(inv.getCustomerId())) {
            BigDecimal left = available(c);
            if (left.signum() <= 0) continue;
            BigDecimal take = left.min(want.subtract(applied));
            pay(inv, take, Payment.PaymentMethod.CUSTOMER_CREDIT, c.getReference(), null, c.getId());
            applied = applied.add(take);
            if (applied.compareTo(want) >= 0) break;
        }
        if (applied.signum() == 0) throw new IllegalStateException("The customer has no credit to use");
        settle(inv);
        auditService.log("INVOICE", inv.getId(), "CUSTOMER_CREDIT_USED", Map.of("invoice", inv.getInvoiceNumber(), "amount", applied));
        return invoiceService.findById(inv.getId());
    }

    /** Pays an invoice with a credit note of the same customer that was kept as credit (Cin7's Allocate credit note). */
    @Transactional
    public InvoiceResponse allocateCreditNote(Long invoiceId, Long creditNoteId, BigDecimal amount) {
        Invoice inv = openSalesInvoice(invoiceId);
        Invoice cn = invoiceRepository.findById(creditNoteId)
                .orElseThrow(() -> new ResourceNotFoundException("Credit note not found: " + creditNoteId));
        if (cn.getKind() != Invoice.Kind.CREDIT_NOTE || cn.getType() != Invoice.InvoiceType.SALES || cn.getStatus() != Invoice.InvoiceStatus.ISSUED) {
            throw new IllegalStateException(cn.getInvoiceNumber() + " is not a credit note with credit left");
        }
        if (!Objects.equals(cn.getCustomerId(), inv.getCustomerId())) {
            throw new IllegalArgumentException(cn.getInvoiceNumber() + " is another customer's credit note");
        }
        BigDecimal left = cn.getTotalAmount().subtract(invoiceService.settled(cn));
        BigDecimal take = left.min(due(inv));
        if (amount != null) take = take.min(amount);
        if (take.compareTo(PENNY) < 0) throw new IllegalStateException(cn.getInvoiceNumber() + " has no credit left");
        pay(inv, take, Payment.PaymentMethod.CREDIT_NOTE, cn.getInvoiceNumber(), cn.getId(), null);
        settle(inv);
        if (invoiceService.settled(cn).compareTo(cn.getTotalAmount()) >= 0) cn.setStatus(Invoice.InvoiceStatus.PAID);
        auditService.log("INVOICE", inv.getId(), "CREDIT_NOTE_ALLOCATED", Map.of("invoice", inv.getInvoiceNumber(),
                "creditNote", cn.getInvoiceNumber(), "amount", take));
        return invoiceService.findById(inv.getId());
    }

    /** Removes a payment entered by mistake (manager or admin, with a reason); kept in the invoice's voided payments. */
    @Transactional
    public InvoiceResponse voidPayment(Long invoiceId, Long paymentId, String reason) {
        requireManager();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the payment is voided");
        Invoice inv = invoiceRepository.findById(invoiceId).orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + invoiceId));
        Payment p = inv.getPayments().stream().filter(x -> x.getId().equals(paymentId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found on " + inv.getInvoiceNumber() + ": " + paymentId));
        if (p.getMethod() == Payment.PaymentMethod.SHOPIFY) {
            throw new IllegalStateException("Shopify payments follow Shopify: refund the order there");
        }
        VoidedPayment v = new VoidedPayment();
        v.setPaymentId(p.getId());
        v.setInvoiceId(inv.getId());
        v.setAmount(p.getAmount());
        v.setMethod(p.getMethod());
        v.setPaidAt(p.getPaidAt());
        v.setPaymentAccountId(p.getPaymentAccountId());
        v.setReference(p.getReference());
        v.setCreditNoteId(p.getCreditNoteId());
        v.setCustomerCreditId(p.getCustomerCreditId());
        v.setSupplierCreditId(p.getSupplierCreditId());
        v.setReason(reason.trim());
        v.setVoidedBy(auditService.currentActorName());
        voidedRepository.save(v);
        inv.getPayments().remove(p);
        paymentRepository.delete(p);
        paymentRepository.flush();
        // Open again; a credit note it used has that credit back
        if (inv.getStatus() == Invoice.InvoiceStatus.PAID && invoiceService.settled(inv).compareTo(inv.getTotalAmount()) < 0) {
            inv.setStatus(Invoice.InvoiceStatus.ISSUED);
        }
        if (p.getCreditNoteId() != null) {
            invoiceRepository.findById(p.getCreditNoteId()).ifPresent(cn -> {
                if (cn.getStatus() == Invoice.InvoiceStatus.PAID && invoiceService.settled(cn).compareTo(cn.getTotalAmount()) < 0) {
                    cn.setStatus(Invoice.InvoiceStatus.ISSUED);
                }
            });
        }
        auditService.log("INVOICE", inv.getId(), "PAYMENT_VOID", Map.of("invoice", inv.getInvoiceNumber(), "amount", p.getAmount(),
                "method", p.getMethod(), "reason", reason.trim()));
        return invoiceService.findById(inv.getId());
    }

    /** Removes customer credit entered by mistake (manager or admin), only while none of it is used. */
    @Transactional
    public void voidCredit(Long customerId, Long creditId, String reason) {
        requireManager();
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Say why the credit is removed");
        CustomerCredit c = creditRepository.findById(creditId).filter(x -> x.getCustomerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Customer credit not found: " + creditId));
        if (paymentRepository.sumUsedFromCustomerCredit(c.getId()).signum() > 0) {
            throw new IllegalStateException("Some of this credit is used on invoices: void those payments first");
        }
        creditRepository.delete(c);
        auditService.log("CUSTOMER", customerId, "CUSTOMER_CREDIT_VOID", Map.of("amount", c.getAmount(),
                "receivedOn", c.getReceivedOn().toString(), "reason", reason.trim()));
    }

    public record OpenCreditNote(Long id, String number, LocalDate date, BigDecimal available) {}

    /** The customer's credit notes kept as credit, with what is left of each (to allocate to an invoice). */
    @Transactional(readOnly = true)
    public List<OpenCreditNote> openCreditNotes(Long customerId) {
        customer(customerId);
        return statementService.openItems(customerId, LocalDate.now()).stream()
                .filter(i -> "CREDIT_NOTE".equals(i.kind()))
                .map(i -> new OpenCreditNote(i.invoiceId(), i.number(), i.date(), i.open().negate())).toList();
    }

    // ---- aged debtors ----

    public record AgedDebtor(Long customerId, String customerName, BigDecimal current, BigDecimal days1to30, BigDecimal days31to60,
                             BigDecimal days61to90, BigDecimal over90, BigDecimal total, BigDecimal credit, BigDecimal creditLimit,
                             boolean onHold) {}

    public record AgedDebtors(LocalDate asAt, List<AgedDebtor> customers, AgedDebtor totals) {}

    /** What each customer owes on a day, aged by days past due (credit in its own column), most owed first. */
    @Transactional(readOnly = true)
    public AgedDebtors agedDebtors(LocalDate asAt) {
        LocalDate day = asAt == null || asAt.isAfter(LocalDate.now()) ? LocalDate.now() : asAt;
        List<AgedDebtor> rows = new ArrayList<>();
        for (Customer c : customerRepository.findAll()) {
            List<StatementService.OpenItem> open = statementService.openItems(c.getId(), day);
            if (open.isEmpty()) continue;
            BigDecimal credit = open.stream().map(StatementService.OpenItem::open).filter(v -> v.signum() < 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            StatementService.Ageing a = StatementService.ageing(open.stream().filter(i -> i.open().signum() > 0).toList());
            rows.add(new AgedDebtor(c.getId(), c.getName(), a.current(), a.days1to30(), a.days31to60(), a.days61to90(), a.over90(),
                    a.total().add(credit), credit, c.getCreditLimit(), c.isOnHold()));
        }
        rows.sort(Comparator.comparing(AgedDebtor::total).reversed().thenComparing(AgedDebtor::customerName));
        AgedDebtor totals = new AgedDebtor(null, "Total", sum(rows, AgedDebtor::current), sum(rows, AgedDebtor::days1to30),
                sum(rows, AgedDebtor::days31to60), sum(rows, AgedDebtor::days61to90), sum(rows, AgedDebtor::over90),
                sum(rows, AgedDebtor::total), sum(rows, AgedDebtor::credit), null, false);
        return new AgedDebtors(day, rows, totals);
    }

    // ---- shared ----

    private static BigDecimal sum(List<AgedDebtor> rows, java.util.function.Function<AgedDebtor, BigDecimal> f) {
        return rows.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2);
    }

    private Invoice openSalesInvoice(Long id) {
        Invoice inv = invoiceRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        if (inv.getType() != Invoice.InvoiceType.SALES || inv.getKind() != Invoice.Kind.INVOICE) {
            throw new IllegalStateException(inv.getInvoiceNumber() + " is not a sales invoice");
        }
        if (inv.getStatus() != Invoice.InvoiceStatus.ISSUED) {
            throw new IllegalStateException(inv.getInvoiceNumber() + " is " + inv.getStatus().name().toLowerCase(Locale.ROOT) + ": nothing to pay");
        }
        if (inv.getCustomerId() == null) throw new IllegalStateException(inv.getInvoiceNumber() + " has no customer");
        return inv;
    }

    private BigDecimal due(Invoice inv) {
        return inv.getTotalAmount().subtract(invoiceService.settled(inv));
    }

    private BigDecimal available(CustomerCredit c) {
        return c.getAmount().subtract(paymentRepository.sumUsedFromCustomerCredit(c.getId()));
    }

    private void pay(Invoice inv, BigDecimal amount, Payment.PaymentMethod method, String reference, Long creditNoteId, Long creditId) {
        Payment p = new Payment();
        p.setInvoice(inv);
        p.setAmount(amount);
        p.setMethod(method);
        p.setReference(reference);
        p.setCreditNoteId(creditNoteId);
        p.setCustomerCreditId(creditId);
        paymentRepository.save(p);
        inv.getPayments().add(p);
        paymentRepository.flush();
    }

    private void settle(Invoice inv) {
        if (invoiceService.settled(inv).compareTo(inv.getTotalAmount()) >= 0) inv.setStatus(Invoice.InvoiceStatus.PAID);
    }

    private CreditView view(CustomerCredit c) {
        BigDecimal used = paymentRepository.sumUsedFromCustomerCredit(c.getId());
        String account = c.getPaymentAccountId() == null ? null
                : accountRepository.findById(c.getPaymentAccountId()).map(PaymentAccount::label).orElse(null);
        String source = c.getSourceInvoiceId() == null ? null
                : invoiceRepository.findById(c.getSourceInvoiceId()).map(Invoice::getInvoiceNumber).orElse(null);
        return new CreditView(c.getId(), c.getReceivedOn(), c.getAmount(), used, c.getAmount().subtract(used), c.getMethod(),
                account, c.getReference(), c.getSourceInvoiceId(), source, c.getNote(), c.getCreatedBy());
    }

    private Customer customer(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    static void requireManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_MANAGER", "ROLE_SUPER_ADMIN")
                .contains(a.getAuthority()));
        if (!ok) throw new AccessDeniedException("Only a manager or admin can do this");
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
