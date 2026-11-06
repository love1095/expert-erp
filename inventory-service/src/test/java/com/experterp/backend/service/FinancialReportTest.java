package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.service.FinancialReportService.Step;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase K2: Xero's Profit and Loss, Balance Sheet (current year and retained earnings), financial year end, lock dates. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class FinancialReportTest {

    @Autowired FinancialReportService reports;
    @Autowired LedgerService ledger;
    @Autowired LedgerPeriodService periods;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    int y = LocalDate.now().getYear();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString().substring(0, 6);
        t.setName("Books " + id);
        t.setSlug("books-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        signIn("ROLE_ADMIN");
        periods.update(new LedgerPeriodService.Periods(31, 12, null, null));     // calendar year
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * Last year: £1,000 sales, £300 advertising (profit £700). This February: £500 sales, £200 cost of sales; in March
     * the owner puts £2,000 in.
     */
    @Test
    void profitAndLossAndBalanceSheetLikeXero() {
        journal(LocalDate.of(y - 1, 6, 1), "090", "200", "1000");
        journal(LocalDate.of(y - 1, 7, 1), "400", "090", "300");
        journal(LocalDate.of(y, 2, 10), "090", "200", "500");
        journal(LocalDate.of(y, 2, 15), "310", "090", "200");
        journal(LocalDate.of(y, 3, 1), "090", "970", "2000");

        FinancialReportService.ProfitAndLoss pl = reports.profitAndLoss(LocalDate.of(y, 2, 1), LocalDate.of(y, 2, 28), 1, Step.MONTH);
        assertThat(pl.columns()).hasSize(2);
        assertThat(pl.columns().get(1).to()).isEqualTo(LocalDate.of(y, 1, 31));                // whole months
        assertThat(pl.tradingIncome().totals()).containsExactly(new BigDecimal("500.00"), new BigDecimal("0.00"));
        assertThat(pl.costOfSales().totals().get(0)).isEqualByComparingTo("200.00");
        assertThat(pl.grossProfit().values().get(0)).isEqualByComparingTo("300.00");
        assertThat(pl.netProfit().values().get(0)).isEqualByComparingTo("300.00");
        FinancialReportService.ProfitAndLoss lastYear = reports.profitAndLoss(LocalDate.of(y - 1, 1, 1), LocalDate.of(y - 1, 12, 31), 0, Step.YEAR);
        assertThat(lastYear.operatingExpenses().rows()).singleElement().satisfies(r -> assertThat(r.name()).isEqualTo("Advertising & Marketing"));
        assertThat(lastYear.netProfit().values().get(0)).isEqualByComparingTo("700.00");

        // Balance sheet at 31 March: bank £3,000; equity = this year's £300 + last year's £700 + the owner's £2,000
        FinancialReportService.BalanceSheet bs = reports.balanceSheet(LocalDate.of(y, 3, 31), 1, Step.YEAR);
        assertThat(bs.warnings()).isEmpty();
        assertThat(bs.assets().get(0).totals().get(0)).isEqualByComparingTo("3000.00");
        assertThat(bs.netAssets().values().get(0)).isEqualByComparingTo("3000.00");
        assertThat(bs.equity().rows()).extracting(FinancialReportService.Row::name)
                .containsExactly("Current Year Earnings", "Retained Earnings", "Owner A Funds Introduced");
        assertThat(bs.equity().rows().get(0).values().get(0)).isEqualByComparingTo("300.00");
        assertThat(bs.equity().rows().get(1).values().get(0)).isEqualByComparingTo("700.00");
        assertThat(bs.equity().totals().get(0)).isEqualByComparingTo("3000.00");
        // A year before (31 March last year): nothing yet
        assertThat(bs.netAssets().values().get(1)).isEqualByComparingTo("0");

        // After the year end, this year's profit becomes retained earnings by itself (no closing journal, as Xero)
        FinancialReportService.BalanceSheet nextYear = reports.balanceSheet(LocalDate.of(y, 12, 31).plusDays(1), 0, Step.MONTH);
        if (!LocalDate.of(y + 1, 1, 1).isAfter(LocalDate.now().plusYears(1))) {
            assertThat(nextYear.equity().rows().get(0).values().get(0)).isEqualByComparingTo("0");
            assertThat(nextYear.equity().rows().get(1).values().get(0)).isEqualByComparingTo("1000.00");
        }
    }

    @Test
    void financialYearStartFollowsTheYearEnd() {
        periods.update(new LedgerPeriodService.Periods(30, 11, null, null));         // Expert Safety: 1 December – 30 November
        assertThat(periods.yearStart(LocalDate.of(2026, 11, 6))).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(periods.yearStart(LocalDate.of(2026, 12, 1))).isEqualTo(LocalDate.of(2026, 12, 1));
        periods.update(new LedgerPeriodService.Periods(28, 2, null, null));
        assertThat(periods.yearStart(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2028, 3, 1).minusYears(1));
    }

    @Test
    void lockDatesStopChangesAndTheLockedFiguresNeverMove() {
        LocalDate feb10 = LocalDate.of(y, 2, 10);
        LedgerService.ManualJournal sale = journal(feb10, "090", "200", "500");
        assertThatThrownBy(() -> periods.update(new LedgerPeriodService.Periods(31, 12, LocalDate.of(y, 1, 31), LocalDate.of(y, 2, 28))))
                .hasMessageContaining("on or before");
        assertThatThrownBy(() -> periods.update(new LedgerPeriodService.Periods(31, 12, LocalDate.now().plusDays(1), null)))
                .hasMessageContaining("future");

        // Locked for everyone but advisers (admins): a manager cannot enter or void in February; the admin still can
        periods.update(new LedgerPeriodService.Periods(31, 12, LocalDate.of(y, 2, 28), null));
        signIn("ROLE_MANAGER");
        assertThatThrownBy(() -> journal(LocalDate.of(y, 2, 20), "090", "200", "1")).hasMessageContaining("locked up to 28/02/" + y);
        assertThatThrownBy(() -> ledger.voidOrDelete(sale.id())).hasMessageContaining("locked");
        signIn("ROLE_ADMIN");
        journal(LocalDate.of(y, 2, 20), "090", "200", "1");

        // Locked for everyone: the admin is stopped too
        periods.update(new LedgerPeriodService.Periods(31, 12, LocalDate.of(y, 2, 28), LocalDate.of(y, 2, 28)));
        assertThatThrownBy(() -> journal(LocalDate.of(y, 2, 21), "090", "200", "1")).hasMessageContaining("locked");

        // Something changed behind the lock anyway (here straight in the database): its reversal is dated 1 March,
        // so February's figures stay as they were reported
        jdbc.update("UPDATE manual_journals SET status = 'VOIDED' WHERE id = ?", sale.id());
        ledger.sync(true);
        assertThat(reports.profitAndLoss(LocalDate.of(y, 2, 1), LocalDate.of(y, 2, 28), 0, Step.MONTH).tradingIncome().totals().get(0))
                .isEqualByComparingTo("501.00");
        List<LedgerService.JournalView> js = ledger.journalReport(null, null, "MJ:" + sale.id());
        assertThat(js).anySatisfy(j -> {
            assertThat(j.reversal()).isTrue();
            assertThat(j.date()).isEqualTo(LocalDate.of(y, 3, 1));
            assertThat(j.narration()).contains("locked period");
        });
    }

    private LedgerService.ManualJournal journal(LocalDate date, String debit, String credit, String amount) {
        BigDecimal a = new BigDecimal(amount);
        return ledger.saveManual(null, new LedgerService.ManualJournalRequest(date, "Test " + debit + "/" + credit,
                List.of(new LedgerService.ManualLine(debit, null, a, null), new LedgerService.ManualLine(credit, null, null, a)), true));
    }

    private void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
