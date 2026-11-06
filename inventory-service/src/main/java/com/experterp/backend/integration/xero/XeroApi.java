package com.experterp.backend.integration.xero;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.util.List;

/**
 * Phase L1: the parts of Xero's Accounting API (api.xro/2.0) the ERP sends and reads, with Xero's own field names
 * (PascalCase, e.g. ContactID, LineAmountTypes) so the same objects go to the real API and the fake. Dates are Xero's
 * "yyyy-MM-dd" strings.
 */
public final class XeroApi {

    private XeroApi() {}

    /**
     * Who and where: the access token and the Xero organisation (the xero-tenant-id header); idempotencyKey (L2): sent as
     * Xero's Idempotency-Key, so a call repeated after a crash gets Xero's first answer instead of a duplicate.
     */
    public record Session(String accessToken, String xeroTenantId, String idempotencyKey) {
        public Session(String accessToken, String xeroTenantId) {
            this(accessToken, xeroTenantId, null);
        }

        public Session withKey(String key) {
            return new Session(accessToken, xeroTenantId, key);
        }
    }

    public record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    /** A Xero organisation the user gave access to (GET /connections). */
    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Organisation(String tenantId, String tenantName, String tenantType) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record Contact(String contactID, String name, String emailAddress, Boolean isCustomer, Boolean isSupplier, String accountNumber,
                          String contactStatus) {
        public static Contact ref(String id) { return new Contact(id, null, null, null, null, null, null); }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record LineItem(String description, BigDecimal quantity, BigDecimal unitAmount, String accountCode, String taxType,
                           BigDecimal taxAmount, BigDecimal lineAmount, String itemCode, BigDecimal discountRate) {}

    /** Type ACCREC = sales invoice, ACCPAY = supplier bill. Status DRAFT, AUTHORISED, PAID, VOIDED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record Invoice(String invoiceID, String type, Contact contact, String date, String dueDate, String invoiceNumber, String reference,
                          String lineAmountTypes, List<LineItem> lineItems, String status, String currencyCode,
                          BigDecimal subTotal, BigDecimal totalTax, BigDecimal total, BigDecimal amountDue, BigDecimal amountPaid) {}

    /** Type ACCRECCREDIT = customer credit note, ACCPAYCREDIT = supplier credit note. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record CreditNote(String creditNoteID, String type, Contact contact, String date, String creditNoteNumber, String reference,
                             String lineAmountTypes, List<LineItem> lineItems, String status, String currencyCode,
                             BigDecimal subTotal, BigDecimal totalTax, BigDecimal total, BigDecimal remainingCredit) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record DocRef(String invoiceID, String creditNoteID, String invoiceNumber, String creditNoteNumber) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record AccountRef(String code, String accountID) {}

    /** A payment on an invoice / bill, or a refund on a credit note. Status AUTHORISED or DELETED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record Payment(String paymentID, DocRef invoice, DocRef creditNote, AccountRef account, String date, BigDecimal amount,
                          String reference, String status, String updatedDateUTC) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record JournalLine(BigDecimal lineAmount, String accountCode, String description, String taxType) {}

    /** LineAmount: debit positive, credit negative; the lines add up to nothing. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record ManualJournal(String manualJournalID, String narration, String date, String status, List<JournalLine> journalLines) {}

    /** A credit note applied to an invoice (PUT CreditNotes/{id}/Allocations). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
    public record Allocation(DocRef invoice, BigDecimal amount, String date) {}
}
