package com.experterp.backend.integration.hmrc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Phase K4: HMRC's Making Tax Digital VAT API (version 1.0) and its OAuth 2.0. One implementation calls HMRC (the
 * sandbox or live, by configuration); tests use a fake.
 */
public interface HmrcClient {

    /** Whether the HMRC developer credentials are set (client id, secret, redirect address). */
    boolean configured();

    /** HMRC's sign-in page, where the business grants the ERP read:vat and write:vat. */
    String authorizeUrl(String state);

    Tokens exchangeCode(String code);

    Tokens refresh(String refreshToken);

    record Tokens(String accessToken, String refreshToken, long expiresInSeconds) {}

    record Obligation(String periodKey, LocalDate start, LocalDate end, LocalDate due, String status, LocalDate received) {}

    List<Obligation> obligations(String vrn, String accessToken, LocalDate from, LocalDate to, Map<String, String> fraudHeaders);

    /** HMRC's nine boxes: 1–5 in pounds and pence, 6–9 in whole pounds. */
    record Submission(String periodKey, BigDecimal vatDueSales, BigDecimal vatDueAcquisitions, BigDecimal totalVatDue,
                      BigDecimal vatReclaimedCurrPeriod, BigDecimal netVatDue, long totalValueSalesExVAT, long totalValuePurchasesExVAT,
                      long totalValueGoodsSuppliedExVAT, long totalAcquisitionsExVAT, boolean finalised) {}

    record Receipt(String processingDate, String formBundleNumber, String paymentIndicator, String chargeRefNumber) {}

    Receipt submit(String vrn, String accessToken, Submission submission, Map<String, String> fraudHeaders);

    /** HMRC said no: its code (e.g. DUPLICATE_SUBMISSION, INVALID_VRN) and message. */
    class HmrcException extends RuntimeException {
        private final int status;
        private final String code;

        public HmrcException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }
}
