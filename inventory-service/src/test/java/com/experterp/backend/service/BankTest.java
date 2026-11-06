package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.Payment;
import com.experterp.backend.entity.PaymentAccount;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.PaymentAccountRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase K3: bank statement import and reconciliation (match, create, transfer, undo), spend money with VAT, like Xero. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class BankTest {

    @Autowired BankService bank;
    @Autowired LedgerService ledger;
    @Autowired SalesLedgerService salesLedger;
    @Autowired CustomerRepository customerRepository;
    @Autowired PaymentAccountRepository paymentAccountRepository;
    @Autowired TenantRepository tenantRepository;

    PaymentAccount current;
    Customer abbey;
    LocalDate today = LocalDate.now();
    DateTimeFormatter uk = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @BeforeEach
    void setUp() {
        String id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Bank " + id);
        t.setSlug("bank-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        current = account("650", "Current Account - RBS", true);
        account("652", "Deposit Account - RBS", false);
        abbey = new Customer();
        abbey.setName("Abbey " + id);
        abbey = customerRepository.save(abbey);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    /**
     * £120 from Abbey is in the ledger (customer credit into 650). The RBS statement has that £120, bank charges of
     * £6.50 and £500 moved to the deposit account.
     */
    @Test
    void importAStatementThenMatchCreateAndTransferLikeXero() throws Exception {
        salesLedger.receiveOnAccount(abbey.getId(), new SalesLedgerService.CreditRequest(new BigDecimal("120.00"), today.minusDays(3),
                current.getId(), null, "INV-77", null));
        String csv = """
                Date,Description,Paid in,Paid out,Balance
                %s,ABBEY LTD INV-77,120.00,,"1,120.00"
                %s,HSBC SERVICE CHARGE,,6.50,"1,113.50"
                %s,TRANSFER TO DEPOSIT,,500.00,613.50
                """.formatted(today.minusDays(2).format(uk), today.minusDays(1).format(uk), today.format(uk));

        BankService.ImportResult preview = bank.importStatement("650", in(csv), "rbs.csv", false);
        assertThat(preview.lines()).isEqualTo(3);
        assertThat(preview.moneyIn()).isEqualByComparingTo("120.00");
        assertThat(preview.moneyOut()).isEqualByComparingTo("506.50");
        assertThat(bank.lines("650", "UNRECONCILED")).isEmpty();                         // a preview writes nothing
        bank.importStatement("650", in(csv), "rbs.csv", true);
        assertThat(bank.importStatement("650", in(csv), "rbs.csv", false).duplicates()).isEqualTo(3);
        assertThatThrownBy(() -> bank.importStatement("650", in(csv), "rbs.csv", true)).hasMessageContaining("imported before");

        // Suggested: the £120 customer credit (same amount, close date, INV-77 in common)
        List<BankService.StatementLine> open = bank.lines("650", "UNRECONCILED");
        assertThat(open).hasSize(3);
        BankService.StatementLine abbeyLine = open.get(0), charges = open.get(1), toDeposit = open.get(2);
        assertThat(abbeyLine.suggestions()).singleElement().satisfies(s -> assertThat(s.item().sourceKey()).startsWith("CC:"));
        assertThat(charges.suggestions()).isEmpty();
        String credit = abbeyLine.suggestions().get(0).item().sourceKey();
        assertThatThrownBy(() -> bank.match(charges.id(), List.of(credit))).hasMessageContaining("must be equal");
        bank.match(abbeyLine.id(), List.of(credit));

        // Bank charges: create a spend money transaction on 404; the transfer: to the deposit account
        bank.create(charges.id(), new BankService.CreateRequest(null, null, List.of(new BankService.CreateLine("404", "Service charge", new BigDecimal("6.50"), null))));
        bank.transfer(toDeposit.id(), "652", null);
        assertThat(bank.lines("650", "UNRECONCILED")).isEmpty();
        assertThat(bank.lines("650", "RECONCILED")).hasSize(3).allSatisfy(l -> assertThat(l.matched()).isNotEmpty());

        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(balance(tb, "650")).isEqualByComparingTo("-386.50");                 // 120 − 6.50 − 500
        assertThat(balance(tb, "652")).isEqualByComparingTo("500.00");
        assertThat(balance(tb, "404")).isEqualByComparingTo("6.50");
        BankService.BankAccount rbs = bank.accounts().stream().filter(a -> a.code().equals("650")).findFirst().orElseThrow();
        assertThat(rbs.toReconcile()).isZero();
        assertThat(rbs.statementBalance()).isEqualByComparingTo("613.50");               // the statement's own running balance
        assertThat(rbs.ledgerBalance()).isEqualByComparingTo("-386.50");

        // Reconciled: the credit cannot be voided until its line is unreconciled
        Long creditId = Long.valueOf(credit.substring(3));
        assertThatThrownBy(() -> salesLedger.voidCredit(abbey.getId(), creditId, "Wrong customer")).hasMessageContaining("unreconcile");
        bank.unreconcile(abbeyLine.id());
        assertThat(bank.lines("650", "UNRECONCILED")).singleElement().satisfies(l -> assertThat(l.suggestions()).hasSize(1));
        salesLedger.voidCredit(abbey.getId(), creditId, "Wrong customer");
        assertThat(bank.lines("650", "UNRECONCILED").get(0).suggestions()).isEmpty();
    }

    @Test
    void spendMoneyWithVatAndOfxStatements() throws Exception {
        // £60 including 20 % VAT: £50 to stationery, £10 VAT
        BankService.Transaction spend = bank.createTransaction(new BankService.TransactionRequest("SPEND", today, "650", null, "Viking", "INV-9",
                true, List.of(new BankService.CreateLine("461", "Paper", new BigDecimal("60.00"), "20% (VAT on Expenses)"))));
        assertThat(spend.total()).isEqualByComparingTo("60.00");
        assertThat(spend.lines()).singleElement().satisfies(l -> {
            assertThat(l.net()).isEqualByComparingTo("50.00");
            assertThat(l.tax()).isEqualByComparingTo("10.00");
        });
        LedgerService.TrialBalance tb = ledger.trialBalance(today);
        assertThat(balance(tb, "461")).isEqualByComparingTo("50.00");
        assertThat(balance(tb, "820")).isEqualByComparingTo("10.00");
        assertThat(balance(tb, "650")).isEqualByComparingTo("-60.00");
        assertThatThrownBy(() -> bank.createTransaction(new BankService.TransactionRequest("SPEND", today, "650", null, null, null, true,
                List.of(new BankService.CreateLine("652", "x", BigDecimal.TEN, null))))).hasMessageContaining("transfer");

        bank.voidTransaction(spend.id());
        assertThat(balance(ledger.trialBalance(today), "461")).isEqualByComparingTo("0");

        String ofx = """
                OFXHEADER:100
                DATA:OFXSGML
                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
                <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>%1$s120000<TRNAMT>-60.00<FITID>1<NAME>VIKING DIRECT<MEMO>INV-9
                <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>%1$s<TRNAMT>250.00<FITID>2<NAME>AE YATES LTD<CHECKNUM>BACS1
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """.formatted(today.format(DateTimeFormatter.BASIC_ISO_DATE));
        BankService.ImportResult r = bank.importStatement("650", in(ofx), "rbs.ofx", false);
        assertThat(r.format()).isEqualTo("OFX");
        assertThat(r.preview()).extracting(BankService.ParsedLine::amount).containsExactly(new BigDecimal("-60.00"), new BigDecimal("250.00"));
        assertThat(r.preview().get(1).reference()).isEqualTo("BACS1");
        assertThatThrownBy(() -> bank.importStatement("610", in(ofx), "x.ofx", false)).hasMessageContaining("not a bank account");
    }

    @Test
    void banksOwnCsvLayoutsAreRead() {
        assertThat(BankService.amount("(12.50)")).isEqualByComparingTo("-12.50");
        assertThat(BankService.amount("£1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(BankService.amount("45.00 DR")).isEqualByComparingTo("-45.00");
        assertThat(BankService.date("06 Nov 2026")).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(BankService.date("2026-11-06")).isEqualTo(LocalDate.of(2026, 11, 6));
        assertThat(BankService.date("06/11/2026")).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    private PaymentAccount account(String code, String name, boolean isDefault) {
        PaymentAccount a = new PaymentAccount();
        a.setCode(code);
        a.setName(name);
        a.setDefaultAccount(isDefault);
        a.setKind(PaymentAccount.Kind.BANK);
        return paymentAccountRepository.save(a);
    }

    private static BigDecimal balance(LedgerService.TrialBalance tb, String code) {
        return tb.rows().stream().filter(r -> r.code().equals(code)).findFirst()
                .map(r -> r.debit() != null ? r.debit() : r.credit().negate()).orElse(BigDecimal.ZERO);
    }

    private static ByteArrayInputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unused")
    private static Payment.PaymentMethod bankMethod() {
        return Payment.PaymentMethod.BANK;
    }
}
