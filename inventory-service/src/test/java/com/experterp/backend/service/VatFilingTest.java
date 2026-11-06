package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.integration.hmrc.HmrcClient;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase K4: the VAT return with spend / receive money, filed returns, and Making Tax Digital against a fake HMRC. */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, VatFilingTest.FakeHmrcConfig.class})
class VatFilingTest {

    /** HMRC as the sandbox answers, keeping what was sent. */
    static class FakeHmrc implements HmrcClient {
        final List<Submission> submissions = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        int refreshes;
        LocalDate periodFrom, periodTo;

        public boolean configured() { return true; }
        public String authorizeUrl(String state) { return "https://test-api.service.hmrc.gov.uk/oauth/authorize?state=" + state; }
        public Tokens exchangeCode(String code) {
            if (!"good-code".equals(code)) throw new HmrcException(400, "invalid_grant", "Bad code");
            return new Tokens("access-1", "refresh-1", 14400);
        }
        public Tokens refresh(String refreshToken) { refreshes++; return new Tokens("access-2", "refresh-2", 14400); }
        public List<Obligation> obligations(String vrn, String accessToken, LocalDate from, LocalDate to, Map<String, String> h) {
            headers.add(h);
            return List.of(new Obligation("26A1", periodFrom, periodTo, periodTo.plusMonths(1).plusDays(7), "O", null));
        }
        public Receipt submit(String vrn, String accessToken, Submission s, Map<String, String> h) {
            headers.add(h);
            submissions.add(s);
            return new Receipt("2026-11-06T10:15:00.000Z", "000123456789", "DD", "XM002610011594");
        }
    }

    @TestConfiguration
    static class FakeHmrcConfig {
        @Bean
        @Primary
        FakeHmrc fakeHmrc() { return new FakeHmrc(); }
    }

    @Autowired MtdVatService mtd;
    @Autowired BankService bank;
    @Autowired LedgerPeriodService periods;
    @Autowired TenantRepository tenantRepository;
    @Autowired FakeHmrc hmrc;
    @Autowired JdbcTemplate jdbc;

    Long tenantId;
    LocalDate from = LocalDate.now().withDayOfMonth(1).minusMonths(3);
    LocalDate to = LocalDate.now().withDayOfMonth(1).minusDays(1);
    MtdVatService.ClientInfo browser = new MtdVatService.ClientInfo("Mozilla/5.0 (X11; Linux x86_64)", "beec798b-b366-47fa-b1f8-92cede14a1ce",
            false, 1920, 1080, 1.0, 24, 1256, 803, "UTC+00:00", "198.51.100.7", 50411);

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString().substring(0, 6);
        t.setName("VAT " + id);
        t.setSlug("vat-" + id);
        tenantId = tenantRepository.save(t).getId();
        TenantContext.set(tenantId);
        signIn("ROLE_ADMIN");
        hmrc.submissions.clear();
        hmrc.headers.clear();
        hmrc.periodFrom = from;
        hmrc.periodTo = to;
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /** In the quarter: £120 received with 20 % VAT (£100 + £20), £60 spent with 20 % VAT (£50 + £10). */
    @Test
    void connectReviewAndSubmitToHmrcThenTheQuarterIsLocked() {
        bank.createTransaction(new BankService.TransactionRequest("RECEIVE", from.plusDays(5), "090", null, "Cash customer", null, true,
                List.of(new BankService.CreateLine("200", "Counter sale", new BigDecimal("120.00"), "20% (VAT on Income)"))));
        bank.createTransaction(new BankService.TransactionRequest("SPEND", from.plusDays(6), "090", null, "Viking", "INV-9", true,
                List.of(new BankService.CreateLine("461", "Paper", new BigDecimal("60.00"), "20% (VAT on Expenses)"))));

        MtdVatService.Review review = mtd.review(from, to);
        VatReturnService.Boxes b = review.vatReturn().boxes();
        assertThat(b.box1()).isEqualByComparingTo("20.00");
        assertThat(b.box4()).isEqualByComparingTo("10.00");
        assertThat(b.box5()).isEqualByComparingTo("10.00");
        assertThat(b.box6()).isEqualByComparingTo("100");
        assertThat(b.box7()).isEqualByComparingTo("50");
        assertThat(review.vatReturn().documents()).extracting(VatReturnService.Document::sourceKey).allMatch(k -> k.startsWith("BT:"));
        assertThat(review.ledgerVatMovement()).isEqualByComparingTo("10.00");          // the VAT account agrees with the return
        assertThat(review.warnings()).isEmpty();
        assertThat(review.declaration()).contains("legal declaration");

        // Connect: the VAT number, HMRC's sign-in, the answer only accepted with our state
        assertThatThrownBy(() -> mtd.setVrn("12345")).hasMessageContaining("9 digits");
        assertThat(mtd.setVrn("GB 123 4567 89").vrn()).isEqualTo("123456789");
        String url = mtd.connect();
        String state = url.substring(url.indexOf("state=") + 6);
        assertThatThrownBy(() -> mtd.callback("good-code", "someone-else")).hasMessageContaining("not for this organisation");
        assertThat(mtd.callback("good-code", state).connected()).isTrue();
        assertThat(jdbc.queryForObject("SELECT access_token FROM hmrc_connections WHERE tenant_id = ?", String.class, tenantId))
                .isNotEqualTo("access-1");                                                // kept encrypted

        List<MtdVatService.Period> open = mtd.obligations(null, null, browser);
        assertThat(open).singleElement().satisfies(p -> {
            assertThat(p.periodKey()).isEqualTo("26A1");
            assertThat(p.filedReturnId()).isNull();
        });

        // Submit: the declaration is required; only an admin files
        assertThatThrownBy(() -> mtd.submit("26A1", from, to, null, false, browser)).hasMessageContaining("declaration");
        signIn("ROLE_MANAGER");
        assertThatThrownBy(() -> mtd.submit("26A1", from, to, null, true, browser)).isInstanceOf(AccessDeniedException.class);
        signIn("ROLE_ADMIN");
        MtdVatService.Filed filed = mtd.submit("26A1", from, to, null, true, browser);
        assertThat(filed.formBundleNumber()).isEqualTo("000123456789");
        assertThat(filed.how()).isEqualTo("MTD");
        HmrcClient.Submission sent = hmrc.submissions.get(0);
        assertThat(sent.vatDueSales()).isEqualByComparingTo("20.00");
        assertThat(sent.netVatDue()).isEqualByComparingTo("10.00");
        assertThat(sent.totalValueSalesExVAT()).isEqualTo(100L);
        assertThat(sent.finalised()).isTrue();
        Map<String, String> h = hmrc.headers.get(hmrc.headers.size() - 1);
        assertThat(h).containsEntry("Gov-Client-Connection-Method", "WEB_APP_VIA_SERVER")
                .containsEntry("Gov-Client-Public-IP", "198.51.100.7")
                .containsEntry("Gov-Client-Window-Size", "width=1256&height=803")
                .containsEntry("Gov-Client-Device-ID", "beec798b-b366-47fa-b1f8-92cede14a1ce")
                .containsKeys("Gov-Client-Public-IP-Timestamp", "Gov-Client-Screens", "Gov-Client-User-IDs", "Gov-Vendor-Version");

        // Filed: the books are locked to the period end, and the period cannot be filed twice
        assertThat(periods.periods().lockDate()).isEqualTo(to);
        assertThat(mtd.obligations(null, null, browser).get(0).filedReturnId()).isEqualTo(filed.id());
        assertThatThrownBy(() -> mtd.submit("26A1", from, to, null, true, browser)).hasMessageContaining("filed already");
        assertThat(mtd.review(from, to).warnings()).anyMatch(w -> w.contains("filed already"));
    }

    @Test
    void aReturnFiledElsewhereIsRecordedAndAnExpiredTokenIsRefreshed() {
        LocalDate oldFrom = from.minusMonths(3), oldTo = from.minusDays(1);
        MtdVatService.Filed manual = mtd.recordManual(oldFrom, oldTo, "Filed from Xero before the switch");
        assertThat(manual.how()).isEqualTo("MANUAL");
        assertThat(manual.boxes().box5()).isEqualByComparingTo("0");
        assertThat(periods.periods().lockDate()).isEqualTo(oldTo);
        assertThatThrownBy(() -> mtd.recordManual(oldFrom.plusDays(10), oldTo, null)).hasMessageContaining("filed already");
        assertThatThrownBy(() -> mtd.recordManual(LocalDate.now(), LocalDate.now().plusDays(30), null)).hasMessageContaining("not ended");

        mtd.setVrn("123456789");
        String url = mtd.connect();
        mtd.callback("good-code", url.substring(url.indexOf("state=") + 6));
        jdbc.update("UPDATE hmrc_connections SET expires_at = now() - interval '1 hour' WHERE tenant_id = ?", tenantId);
        int before = hmrc.refreshes;
        mtd.obligations(null, null, browser);
        assertThat(hmrc.refreshes).isEqualTo(before + 1);
        assertThat(mtd.filed()).hasSize(1);
    }

    private void signIn(String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority(role))));
    }
}
