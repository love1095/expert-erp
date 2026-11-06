package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase K5: the accountant — Xero's Adviser rights, the Accountant role, report exports and the year-end pack. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccountantTest {

    @Autowired LedgerService ledger;
    @Autowired LedgerPeriodService periods;
    @Autowired LedgerExportService exports;
    @Autowired MtdVatService vat;
    @Autowired AccessRoleService roles;
    @Autowired TenantRepository tenantRepository;

    int y = LocalDate.now().getYear();

    @BeforeEach
    void setUp() {
        Tenant t = new Tenant();
        String id = UUID.randomUUID().toString().substring(0, 6);
        t.setName("Accounts " + id);
        t.setSlug("accounts-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        signIn("ROLE_ADMIN");
        periods.update(new LedgerPeriodService.Periods(31, 12, null, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void theAccountantIsAnAdviserWhoWorksInTheLockedYearAndFilesVat() {
        assertThat(roles.roles()).anySatisfy(r -> {
            assertThat(r.name()).isEqualTo("Accountant");
            assertThat(r.permissions()).contains("ADVISER", "ACCOUNTING", "REPORTS_FINANCIAL").doesNotContain("SALE_EDIT", "PURCHASE_EDIT");
        });
        periods.update(new LedgerPeriodService.Periods(31, 12, LocalDate.of(y - 1, 12, 31), null));

        // A manager cannot post into last year; the accountant (manager login with the Adviser tick) can, and files VAT
        signIn("ROLE_MANAGER", "PERM_ACCOUNTING");
        assertThatThrownBy(() -> journal(LocalDate.of(y - 1, 12, 20), "Year-end accrual")).hasMessageContaining("locked");
        assertThatThrownBy(() -> vat.recordManual(LocalDate.of(y - 1, 10, 1), LocalDate.of(y - 1, 12, 31), null)).hasMessageContaining("adviser");
        signIn("ROLE_MANAGER", "PERM_ACCOUNTING", "PERM_ADVISER");
        journal(LocalDate.of(y - 1, 12, 20), "Year-end accrual");
        assertThat(vat.recordManual(LocalDate.of(y - 1, 10, 1), LocalDate.of(y - 1, 12, 31), "Filed by the accountant").how()).isEqualTo("MANUAL");

        // Locked for all users: the adviser is stopped too
        signIn("ROLE_ADMIN");
        periods.update(new LedgerPeriodService.Periods(31, 12, LocalDate.of(y - 1, 12, 31), LocalDate.of(y - 1, 12, 31)));
        signIn("ROLE_MANAGER", "PERM_ACCOUNTING", "PERM_ADVISER");
        assertThatThrownBy(() -> journal(LocalDate.of(y - 1, 12, 21), "Too late")).hasMessageContaining("locked");
    }

    @Test
    void reportsExportAsCsvAndTheYearEndPackHasEverything() throws Exception {
        journal(LocalDate.of(y - 1, 6, 1), "Cash sale");                                    // £250 sales last year
        String tb = new String(exports.trialBalanceCsv(LocalDate.of(y - 1, 12, 31)), StandardCharsets.UTF_8);
        assertThat(tb).startsWith("﻿Code,Account,Type,Debit,Credit").contains("200,Sales,Revenue,,250.00").contains("Total,,250.00,250.00");
        String pl = new String(exports.profitAndLossCsv(LocalDate.of(y - 1, 1, 1), LocalDate.of(y - 1, 12, 31), 0, FinancialReportService.Step.YEAR), StandardCharsets.UTF_8);
        assertThat(pl).contains("Trading Income").contains("Net Profit,250.00");
        String gl = new String(exports.generalLedgerCsv(LocalDate.of(y - 1, 1, 1), LocalDate.of(y - 1, 12, 31)), StandardCharsets.UTF_8);
        assertThat(gl.lines().count()).isEqualTo(3);                                        // header + two lines

        Map<String, String> pack = unzip(exports.yearEndPack(LocalDate.of(y - 1, 12, 31)).zip());
        assertThat(pack.keySet()).anyMatch(n -> n.startsWith("1 Profit and loss")).anyMatch(n -> n.startsWith("2 Balance sheet"))
                .anyMatch(n -> n.startsWith("3 Trial balance")).anyMatch(n -> n.startsWith("4 General ledger"))
                .anyMatch(n -> n.startsWith("5 Aged debtors")).anyMatch(n -> n.startsWith("6 Aged creditors"))
                .anyMatch(n -> n.startsWith("7 VAT returns")).anyMatch(n -> n.startsWith("8 Chart of accounts")).contains("README.txt");
        assertThat(pack.entrySet().stream().filter(e -> e.getKey().startsWith("2 Balance sheet")).findFirst().orElseThrow().getValue())
                .contains("Current Year Earnings").contains("250.00");
        assertThat(pack.entrySet().stream().filter(e -> e.getKey().startsWith("8 Chart")).findFirst().orElseThrow().getValue())
                .startsWith("﻿*Code,*Name,*Type,*Tax Code,Description").contains("610,Accounts Receivable,Current Asset");
        assertThatThrownBy(() -> exports.yearEndPack(LocalDate.of(y - 1, 6, 30))).hasMessageContaining("last day of a financial year");
    }

    private void journal(LocalDate date, String narration) {
        BigDecimal a = new BigDecimal("250");
        ledger.saveManual(null, new LedgerService.ManualJournalRequest(date, narration,
                List.of(new LedgerService.ManualLine("090", null, a, null), new LedgerService.ManualLine("200", null, null, a)), true));
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
        }
        return out;
    }

    private void signIn(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("someone", null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
}
