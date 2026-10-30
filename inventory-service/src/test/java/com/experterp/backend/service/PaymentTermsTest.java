package com.experterp.backend.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentTermsTest {

    private static final LocalDate OCT_30 = LocalDate.of(2026, 10, 30);

    @Test
    void cin7TermsGiveTheDueDate() {
        // The terms used by Expert Safety's customers in Cin7
        assertThat(PaymentTerms.dueDate("30 Day EOM", OCT_30)).isEqualTo(LocalDate.of(2026, 11, 30));
        assertThat(PaymentTerms.dueDate("60 Day EOM", OCT_30)).isEqualTo(LocalDate.of(2026, 12, 30));
        assertThat(PaymentTerms.dueDate("60 Day", OCT_30)).isEqualTo(LocalDate.of(2026, 12, 29));
        assertThat(PaymentTerms.dueDate("Cash", OCT_30)).isEqualTo(OCT_30);
        assertThat(PaymentTerms.dueDate(null, OCT_30)).isEqualTo(OCT_30);
        assertThat(PaymentTerms.dueDate("14 days", LocalDate.of(2026, 2, 20))).isEqualTo(LocalDate.of(2026, 3, 6));
        assertThat(PaymentTerms.dueDate("30 Days EOM", LocalDate.of(2028, 2, 3))).isEqualTo(LocalDate.of(2028, 3, 30));
    }
}
