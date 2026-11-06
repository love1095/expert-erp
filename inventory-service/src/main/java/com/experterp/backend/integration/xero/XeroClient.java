package com.experterp.backend.integration.xero;

import com.experterp.backend.integration.xero.XeroApi.*;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Phase L1: Xero's Accounting API and its OAuth 2.0. {@code APP_XERO_MODE=fake} (the default) uses {@link FakeXeroClient},
 * {@code live} uses {@link HttpXeroClient} with the owner's Xero developer app. Every call can throw {@link XeroException}.
 */
public interface XeroClient {

    /** fake or live */
    String mode();

    /** Whether the client can connect (live: the developer app's id, secret and redirect address are set). */
    boolean configured();

    /** Xero's sign-in page where the business grants access (fake: straight back to our callback). */
    String authorizeUrl(String state);

    Tokens exchangeCode(String code);

    Tokens refresh(String refreshToken);

    /** The Xero organisations the user gave access to. */
    List<Organisation> connections(String accessToken);

    /** An existing contact with exactly this name (Xero's names are unique), to link instead of creating a second. */
    java.util.Optional<Contact> findContactByName(Session s, String name);

    /** Creates a contact (no ContactID) or updates one. */
    Contact saveContact(Session s, Contact contact);

    /** Creates or updates a sales invoice (ACCREC) or supplier bill (ACCPAY). */
    Invoice saveInvoice(Session s, Invoice invoice);

    /** Voids an invoice or bill without payments (Status VOIDED). */
    Invoice voidInvoice(Session s, String invoiceId);

    /** Creates a customer (ACCRECCREDIT) or supplier (ACCPAYCREDIT) credit note. */
    CreditNote saveCreditNote(Session s, CreditNote creditNote);

    /** Applies a credit note to an invoice or bill. */
    Allocation allocate(Session s, String creditNoteId, Allocation allocation);

    /** A payment on an invoice / bill, or a refund on a credit note. */
    Payment createPayment(Session s, Payment payment);

    /** Xero has no edit for payments: they are deleted (Status DELETED). */
    void deletePayment(Session s, String paymentId);

    ManualJournal saveManualJournal(Session s, ManualJournal journal);

    /** The organisation's tax rates (to turn the ERP's VAT rate names into Xero's TaxType codes). */
    List<TaxRate> taxRates(Session s);

    /** An invoice or bill by its number (ACCPAY: the supplier's number), e.g. one sent earlier in a CSV file. */
    java.util.Optional<Invoice> findInvoiceByNumber(Session s, String type, String number);

    /** A credit note by its number, e.g. one sent earlier in a CSV file. */
    java.util.Optional<CreditNote> findCreditNoteByNumber(Session s, String type, String number);

    /** Payments made or changed in Xero since a moment (L4: pulled back into the ERP). */
    List<Payment> payments(Session s, OffsetDateTime modifiedSince);
}
