package com.experterp.backend.service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Due date of an invoice from the customer's payment terms, written as in Cin7:
 * "30 Day EOM" = 30 days after the end of the invoice month, "60 Day" = 60 days after the invoice date,
 * "Cash" (or COD, due on receipt, proforma, nothing) = the invoice date. Unknown terms: the invoice date.
 */
public final class PaymentTerms {

    private static final Pattern EOM = Pattern.compile("(\\d+)\\s*days?\\s*(eom|after end of month|end of month)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAYS = Pattern.compile("(\\d+)\\s*days?", Pattern.CASE_INSENSITIVE);

    private PaymentTerms() {}

    public static LocalDate dueDate(String term, LocalDate invoiceDate) {
        if (term == null || term.isBlank()) return invoiceDate;
        String t = term.trim().toLowerCase(Locale.ROOT);
        Matcher eom = EOM.matcher(t);
        if (eom.find()) {
            return invoiceDate.withDayOfMonth(invoiceDate.lengthOfMonth()).plusDays(Long.parseLong(eom.group(1)));
        }
        Matcher days = DAYS.matcher(t);
        if (days.find()) return invoiceDate.plusDays(Long.parseLong(days.group(1)));
        return invoiceDate;
    }
}
