package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.*;
import com.experterp.backend.multitenancy.TenantContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase L1: an in-memory Xero (APP_XERO_MODE=fake, the default) — one "Demo Company" per ERP organisation, Xero-style
 * IDs, Xero's main validation rules (a contact on every document, unique sales invoice numbers, a payment no more than
 * what is due, no payment on a voided or draft document, a journal that balances). It can be told to fail the next
 * calls the way Xero does: a validation error, a rate limit (429 with Retry-After), an expired token or Xero down.
 * Nothing leaves the server.
 */
@Component
@ConditionalOnProperty(name = "app.xero.mode", havingValue = "fake", matchIfMissing = true)
public class FakeXeroClient implements XeroClient {

    public enum Failure { VALIDATION, RATE_LIMIT, EXPIRED_TOKEN, DOWN }

    /** One fake Xero organisation. */
    public static final class Org {
        public final String tenantId, name;
        public final Map<String, Contact> contacts = new ConcurrentHashMap<>();
        public final Map<String, Invoice> invoices = new ConcurrentHashMap<>();
        public final Map<String, CreditNote> creditNotes = new ConcurrentHashMap<>();
        public final Map<String, Payment> payments = new ConcurrentHashMap<>();
        public final Map<String, ManualJournal> journals = new ConcurrentHashMap<>();
        public final List<Allocation> allocations = Collections.synchronizedList(new ArrayList<>());
        final Deque<Failure> failures = new ArrayDeque<>();
        /** Answers already given per Idempotency-Key: a repeat gets the same answer and changes nothing. */
        final Map<String, Object> answers = new ConcurrentHashMap<>();
        /** Every API call made (endpoint names), to check nothing was sent twice. */
        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        Org(String tenantId, String name) {
            this.tenantId = tenantId;
            this.name = name;
        }
    }

    private final Map<String, Org> orgs = new ConcurrentHashMap<>();
    /** access token → [Xero tenant id, expiry (epoch seconds)] */
    private final Map<String, Object[]> tokens = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>();

    @Override
    public String mode() { return "fake"; }

    @Override
    public boolean configured() { return true; }

    /** No Xero sign-in page: straight back to the ERP's callback with a fake code. */
    @Override
    public String authorizeUrl(String state) {
        return "/finance/xero/callback?code=fake-code-" + TenantContext.get() + "&state=" + state;
    }

    @Override
    public Tokens exchangeCode(String code) {
        if (code == null || !code.startsWith("fake-code-")) throw new XeroException(XeroException.Kind.VALIDATION, 400, "invalid_grant");
        String tenant = "fake-org-" + code.substring("fake-code-".length());
        orgs.computeIfAbsent(tenant, id -> new Org(id, "Demo Company (UK) — fake"));
        return issue(tenant);
    }

    @Override
    public Tokens refresh(String refreshToken) {
        String tenant = refreshTokens.remove(refreshToken);
        if (tenant == null) throw new XeroException(XeroException.Kind.UNAUTHORIZED, 400, "invalid_grant: the refresh token was used or has expired");
        return issue(tenant);
    }

    private Tokens issue(String tenant) {
        String access = "fake-access-" + UUID.randomUUID(), refresh = "fake-refresh-" + UUID.randomUUID();
        tokens.put(access, new Object[]{tenant, Instant.now().plusSeconds(1800).getEpochSecond()});
        refreshTokens.put(refresh, tenant);
        return new Tokens(access, refresh, 1800);
    }

    @Override
    public List<Organisation> connections(String accessToken) {
        Object[] t = tokens.get(accessToken);
        if (t == null) throw new XeroException(XeroException.Kind.UNAUTHORIZED, 401, "TokenExpired");
        Org o = orgs.get((String) t[0]);
        return List.of(new Organisation(o.tenantId, o.name, "ORGANISATION"));
    }

    // ---- simulation and inspection (tests, and a developer trying the queue) ----

    /** The fake organisation of an ERP organisation (created on first use). */
    public Org org(Long erpTenantId) {
        return orgs.computeIfAbsent("fake-org-" + erpTenantId, id -> new Org(id, "Demo Company (UK) — fake"));
    }

    /** The next calls to this organisation fail, in order, the way Xero fails. */
    public void failNext(String xeroTenantId, Failure... failures) {
        Org o = orgs.computeIfAbsent(xeroTenantId, id -> new Org(id, "Demo Company (UK) — fake"));
        synchronized (o.failures) {
            o.failures.addAll(List.of(failures));
        }
    }

    /** A payment someone entered in Xero itself (for L4: pulled back into the ERP). */
    public Payment payInXero(String xeroTenantId, String invoiceId, BigDecimal amount, String accountCode, String date) {
        Org o = orgs.get(xeroTenantId);
        return pay(o, new Payment(null, new DocRef(invoiceId, null, null, null), null, new AccountRef(accountCode, null), date, amount, "Paid in Xero", null, null));
    }

    // ---- the API ----

    private Org call(Session s, String endpoint) {
        Object[] t = tokens.get(s.accessToken());
        if (t == null || !Objects.equals(t[0], s.xeroTenantId())) throw new XeroException(XeroException.Kind.UNAUTHORIZED, 401, "AuthenticationUnsuccessful");
        Org o = orgs.get(s.xeroTenantId());
        Failure f;
        synchronized (o.failures) {
            f = o.failures.poll();
        }
        if (f != null) {
            switch (f) {
                case VALIDATION -> throw new XeroException(XeroException.Kind.VALIDATION, 400, "A validation exception occurred (simulated)");
                case RATE_LIMIT -> throw new XeroException(XeroException.Kind.RATE_LIMIT, 429, "Rate limit exceeded (simulated)", 1);
                case EXPIRED_TOKEN -> {
                    tokens.remove(s.accessToken());
                    throw new XeroException(XeroException.Kind.UNAUTHORIZED, 401, "TokenExpired (simulated)");
                }
                case DOWN -> throw new XeroException(XeroException.Kind.UNAVAILABLE, 503, "Service Unavailable (simulated)");
            }
        }
        if (Instant.now().getEpochSecond() > (Long) t[1]) throw new XeroException(XeroException.Kind.UNAUTHORIZED, 401, "TokenExpired");
        o.calls.add(endpoint);
        return o;
    }

    @SuppressWarnings("unchecked")
    private <T> T once(Session s, Org o, java.util.function.Supplier<T> work) {
        if (s.idempotencyKey() == null) return work.get();
        Object seen = o.answers.get(s.idempotencyKey());
        if (seen != null) return (T) seen;
        T answer = work.get();
        o.answers.put(s.idempotencyKey(), answer);
        return answer;
    }

    @Override
    public Optional<Contact> findContactByName(Session s, String name) {
        Org o = call(s, "Contacts?where");
        return o.contacts.values().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    public Contact saveContact(Session s, Contact c) {
        Org o = call(s, "Contacts");
        return once(s, o, () -> doSaveContact(o, c));
    }

    private Contact doSaveContact(Org o, Contact c) {
        if (c.name() == null || c.name().isBlank()) throw new XeroException(XeroException.Kind.VALIDATION, 400, "The contact name must be specified");
        // Xero: contact names are unique within an organisation
        boolean taken = o.contacts.values().stream().anyMatch(x -> x.name().equalsIgnoreCase(c.name()) && !x.contactID().equals(c.contactID()));
        if (taken) throw new XeroException(XeroException.Kind.VALIDATION, 400, "The contact name " + c.name() + " is already assigned to another contact");
        String id = c.contactID() != null ? c.contactID() : UUID.randomUUID().toString();
        Contact saved = new Contact(id, c.name(), c.emailAddress(), c.isCustomer(), c.isSupplier(), c.accountNumber(), "ACTIVE");
        o.contacts.put(id, saved);
        return saved;
    }

    @Override
    public Invoice saveInvoice(Session s, Invoice i) {
        Org o = call(s, "Invoices");
        return once(s, o, () -> doSaveInvoice(o, i));
    }

    private Invoice doSaveInvoice(Org o, Invoice i) {
        requireContact(o, i.contact());
        if (!Set.of("ACCREC", "ACCPAY").contains(i.type())) throw new XeroException(XeroException.Kind.VALIDATION, 400, "Type must be ACCREC or ACCPAY");
        if (i.lineItems() == null || i.lineItems().isEmpty()) throw new XeroException(XeroException.Kind.VALIDATION, 400, "At least one line item must be specified");
        if ("ACCREC".equals(i.type()) && i.invoiceNumber() != null && o.invoices.values().stream()
                .anyMatch(x -> "ACCREC".equals(x.type()) && i.invoiceNumber().equals(x.invoiceNumber()) && !x.invoiceID().equals(i.invoiceID()))) {
            throw new XeroException(XeroException.Kind.VALIDATION, 400, "Invoice # must be unique.");
        }
        Invoice before = i.invoiceID() == null ? null : o.invoices.get(i.invoiceID());
        if (i.invoiceID() != null && before == null) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "The resource you're looking for cannot be found");
        if (before != null && before.amountPaid().signum() > 0) {
            throw new XeroException(XeroException.Kind.VALIDATION, 400, "Invoice not of valid status for modification: it has payments");
        }
        BigDecimal[] t = totals(i.lineItems(), i.lineAmountTypes());
        String id = i.invoiceID() != null ? i.invoiceID() : UUID.randomUUID().toString();
        String status = i.status() == null ? "DRAFT" : i.status();
        Invoice saved = new Invoice(id, i.type(), o.contacts.get(i.contact().contactID()), i.date(), i.dueDate(), i.invoiceNumber(), i.reference(),
                i.lineAmountTypes() == null ? "Exclusive" : i.lineAmountTypes(), i.lineItems(), status, "GBP", t[0], t[1], t[2], t[2], BigDecimal.ZERO.setScale(2));
        o.invoices.put(id, saved);
        return saved;
    }

    @Override
    public Invoice voidInvoice(Session s, String invoiceId) {
        Org o = call(s, "Invoices");
        Invoice i = o.invoices.get(invoiceId);
        if (i == null) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "The resource you're looking for cannot be found");
        if (i.amountPaid().signum() > 0) throw new XeroException(XeroException.Kind.VALIDATION, 400, "Payments must be removed before the invoice is voided");
        Invoice v = withStatus(i, "VOIDED", i.amountPaid());
        o.invoices.put(invoiceId, v);
        return v;
    }

    @Override
    public CreditNote saveCreditNote(Session s, CreditNote c) {
        Org o = call(s, "CreditNotes");
        return once(s, o, () -> doSaveCreditNote(o, c));
    }

    private CreditNote doSaveCreditNote(Org o, CreditNote c) {
        requireContact(o, c.contact());
        if (!Set.of("ACCRECCREDIT", "ACCPAYCREDIT").contains(c.type())) throw new XeroException(XeroException.Kind.VALIDATION, 400, "Type must be ACCRECCREDIT or ACCPAYCREDIT");
        if (c.lineItems() == null || c.lineItems().isEmpty()) throw new XeroException(XeroException.Kind.VALIDATION, 400, "At least one line item must be specified");
        BigDecimal[] t = totals(c.lineItems(), c.lineAmountTypes());
        String id = c.creditNoteID() != null ? c.creditNoteID() : UUID.randomUUID().toString();
        CreditNote saved = new CreditNote(id, c.type(), o.contacts.get(c.contact().contactID()), c.date(), c.creditNoteNumber(), c.reference(),
                c.lineAmountTypes() == null ? "Exclusive" : c.lineAmountTypes(), c.lineItems(), c.status() == null ? "DRAFT" : c.status(), "GBP",
                t[0], t[1], t[2], t[2]);
        o.creditNotes.put(id, saved);
        return saved;
    }

    @Override
    public Allocation allocate(Session s, String creditNoteId, Allocation a) {
        Org o = call(s, "Allocations");
        return once(s, o, () -> doAllocate(o, creditNoteId, a));
    }

    private Allocation doAllocate(Org o, String creditNoteId, Allocation a) {
        CreditNote cn = o.creditNotes.get(creditNoteId);
        Invoice inv = a.invoice() == null ? null : o.invoices.get(a.invoice().invoiceID());
        if (cn == null || inv == null) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "The resource you're looking for cannot be found");
        if (a.amount().compareTo(cn.remainingCredit()) > 0 || a.amount().compareTo(inv.amountDue()) > 0) {
            throw new XeroException(XeroException.Kind.VALIDATION, 400, "The amount allocated is more than the credit remaining or the amount due");
        }
        o.creditNotes.put(creditNoteId, new CreditNote(cn.creditNoteID(), cn.type(), cn.contact(), cn.date(), cn.creditNoteNumber(), cn.reference(),
                cn.lineAmountTypes(), cn.lineItems(), cn.remainingCredit().subtract(a.amount()).signum() == 0 ? "PAID" : cn.status(), cn.currencyCode(),
                cn.subTotal(), cn.totalTax(), cn.total(), cn.remainingCredit().subtract(a.amount())));
        BigDecimal paid = inv.amountPaid().add(a.amount());
        o.invoices.put(inv.invoiceID(), withStatus(inv, paid.compareTo(inv.total()) >= 0 ? "PAID" : inv.status(), paid));
        o.allocations.add(a);
        return a;
    }

    @Override
    public Payment createPayment(Session s, Payment p) {
        Org o = call(s, "Payments");
        return once(s, o, () -> pay(o, p));
    }

    private Payment pay(Org o, Payment p) {
        if (p.account() == null || p.account().code() == null) throw new XeroException(XeroException.Kind.VALIDATION, 400, "Account code must be specified");
        if (p.amount() == null || p.amount().signum() <= 0) throw new XeroException(XeroException.Kind.VALIDATION, 400, "Payment amount must be greater than zero");
        String id = UUID.randomUUID().toString(), now = OffsetDateTime.now(ZoneOffset.UTC).toString();
        if (p.invoice() != null) {
            Invoice inv = o.invoices.get(p.invoice().invoiceID());
            if (inv == null) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "The resource you're looking for cannot be found");
            if (!"AUTHORISED".equals(inv.status())) throw new XeroException(XeroException.Kind.VALIDATION, 400, "Payment can only be made against Authorised documents");
            if (p.amount().compareTo(inv.amountDue()) > 0) {
                throw new XeroException(XeroException.Kind.VALIDATION, 400, "Payment amount exceeds the amount outstanding on this document");
            }
            BigDecimal paid = inv.amountPaid().add(p.amount());
            o.invoices.put(inv.invoiceID(), withStatus(inv, paid.compareTo(inv.total()) >= 0 ? "PAID" : "AUTHORISED", paid));
        } else if (p.creditNote() != null) {
            CreditNote cn = o.creditNotes.get(p.creditNote().creditNoteID());
            if (cn == null) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "The resource you're looking for cannot be found");
            if (p.amount().compareTo(cn.remainingCredit()) > 0) throw new XeroException(XeroException.Kind.VALIDATION, 400, "The refund is more than the credit remaining");
            BigDecimal left = cn.remainingCredit().subtract(p.amount());
            o.creditNotes.put(cn.creditNoteID(), new CreditNote(cn.creditNoteID(), cn.type(), cn.contact(), cn.date(), cn.creditNoteNumber(), cn.reference(),
                    cn.lineAmountTypes(), cn.lineItems(), left.signum() == 0 ? "PAID" : cn.status(), cn.currencyCode(), cn.subTotal(), cn.totalTax(), cn.total(), left));
        } else {
            throw new XeroException(XeroException.Kind.VALIDATION, 400, "A payment needs an invoice or a credit note");
        }
        Payment saved = new Payment(id, p.invoice(), p.creditNote(), p.account(), p.date(), p.amount().setScale(2, RoundingMode.HALF_UP), p.reference(), "AUTHORISED", now);
        o.payments.put(id, saved);
        return saved;
    }

    @Override
    public void deletePayment(Session s, String paymentId) {
        Org o = call(s, "Payments");
        Payment p = o.payments.get(paymentId);
        if (p == null || "DELETED".equals(p.status())) throw new XeroException(XeroException.Kind.NOT_FOUND, 404, "The resource you're looking for cannot be found");
        o.payments.put(paymentId, new Payment(p.paymentID(), p.invoice(), p.creditNote(), p.account(), p.date(), p.amount(), p.reference(), "DELETED",
                OffsetDateTime.now(ZoneOffset.UTC).toString()));
        if (p.invoice() != null) {
            Invoice inv = o.invoices.get(p.invoice().invoiceID());
            BigDecimal paid = inv.amountPaid().subtract(p.amount());
            o.invoices.put(inv.invoiceID(), withStatus(inv, "AUTHORISED", paid));
        }
    }

    @Override
    public ManualJournal saveManualJournal(Session s, ManualJournal j) {
        Org o = call(s, "ManualJournals");
        return once(s, o, () -> doSaveJournal(o, j));
    }

    private ManualJournal doSaveJournal(Org o, ManualJournal j) {
        if (j.journalLines() == null || j.journalLines().size() < 2) throw new XeroException(XeroException.Kind.VALIDATION, 400, "A journal needs at least two lines");
        BigDecimal sum = j.journalLines().stream().map(JournalLine::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.signum() != 0) throw new XeroException(XeroException.Kind.VALIDATION, 400, "The total debits must equal the total credits");
        String id = j.manualJournalID() != null ? j.manualJournalID() : UUID.randomUUID().toString();
        ManualJournal saved = new ManualJournal(id, j.narration(), j.date(), j.status() == null ? "DRAFT" : j.status(), j.journalLines());
        o.journals.put(id, saved);
        return saved;
    }

    /** Xero's UK tax rates (names as Xero shows them, as in the CSV templates). */
    static final List<TaxRate> UK_TAX_RATES = List.of(
            new TaxRate("20% (VAT on Income)", "OUTPUT2", new BigDecimal("20"), "ACTIVE"),
            new TaxRate("20% (VAT on Expenses)", "INPUT2", new BigDecimal("20"), "ACTIVE"),
            new TaxRate("5% (VAT on Income)", "RROUTPUT", new BigDecimal("5"), "ACTIVE"),
            new TaxRate("5% (VAT on Expenses)", "RRINPUT", new BigDecimal("5"), "ACTIVE"),
            new TaxRate("Zero Rated Income", "ZERORATEDOUTPUT", BigDecimal.ZERO, "ACTIVE"),
            new TaxRate("Zero Rated Expenses", "ZERORATEDINPUT", BigDecimal.ZERO, "ACTIVE"),
            new TaxRate("Exempt Income", "EXEMPTOUTPUT", BigDecimal.ZERO, "ACTIVE"),
            new TaxRate("Exempt Expenses", "EXEMPTINPUT", BigDecimal.ZERO, "ACTIVE"),
            new TaxRate("No VAT", "NONE", BigDecimal.ZERO, "ACTIVE"),
            new TaxRate("20% (Reverse Charge)", "REVERSECHARGES", new BigDecimal("20"), "ACTIVE"),
            new TaxRate("EC Acquisitions (20%)", "ECACQUISITIONS", new BigDecimal("20"), "ACTIVE"),
            new TaxRate("Full VAT", "GSTONIMPORTS", new BigDecimal("100"), "ACTIVE"));

    @Override
    public List<TaxRate> taxRates(Session s) {
        call(s, "TaxRates");
        return UK_TAX_RATES;
    }

    @Override
    public Optional<Invoice> findInvoiceByNumber(Session s, String type, String number) {
        Org o = call(s, "Invoices?where");
        return o.invoices.values().stream().filter(i -> i.type().equals(type) && number.equals(i.invoiceNumber()) && !"VOIDED".equals(i.status())).findFirst();
    }

    @Override
    public Optional<CreditNote> findCreditNoteByNumber(Session s, String type, String number) {
        Org o = call(s, "CreditNotes?where");
        return o.creditNotes.values().stream().filter(c -> c.type().equals(type) && number.equals(c.creditNoteNumber())).findFirst();
    }

    @Override
    public List<Payment> payments(Session s, OffsetDateTime modifiedSince) {
        Org o = call(s, "Payments");
        return o.payments.values().stream()
                .filter(p -> modifiedSince == null || OffsetDateTime.parse(p.updatedDateUTC()).isAfter(modifiedSince))
                .sorted(Comparator.comparing(Payment::updatedDateUTC)).toList();
    }

    // ---- shared ----

    private static void requireContact(Org o, Contact c) {
        if (c == null || c.contactID() == null || !o.contacts.containsKey(c.contactID())) {
            throw new XeroException(XeroException.Kind.VALIDATION, 400, "A Contact must be specified for this type of transaction");
        }
    }

    // Xero works out the totals: Exclusive (net + tax), Inclusive (gross includes tax), NoTax
    private static BigDecimal[] totals(List<LineItem> lines, String amountTypes) {
        BigDecimal sub = BigDecimal.ZERO, tax = BigDecimal.ZERO;
        for (LineItem l : lines) {
            BigDecimal amount = l.lineAmount() != null ? l.lineAmount()
                    : l.quantity().multiply(l.unitAmount()).multiply(BigDecimal.ONE.subtract(Optional.ofNullable(l.discountRate()).orElse(BigDecimal.ZERO)
                    .divide(BigDecimal.valueOf(100)))).setScale(2, RoundingMode.HALF_UP);
            BigDecimal t = l.taxAmount() == null ? BigDecimal.ZERO : l.taxAmount();
            if ("Inclusive".equals(amountTypes)) amount = amount.subtract(t);
            sub = sub.add(amount);
            tax = tax.add(t);
        }
        sub = sub.setScale(2, RoundingMode.HALF_UP);
        tax = tax.setScale(2, RoundingMode.HALF_UP);
        return new BigDecimal[]{sub, tax, sub.add(tax)};
    }

    private static Invoice withStatus(Invoice i, String status, BigDecimal paid) {
        return new Invoice(i.invoiceID(), i.type(), i.contact(), i.date(), i.dueDate(), i.invoiceNumber(), i.reference(), i.lineAmountTypes(), i.lineItems(),
                status, i.currencyCode(), i.subTotal(), i.totalTax(), i.total(), i.total().subtract(paid), paid);
    }
}
