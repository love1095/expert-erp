package com.experterp.backend.flow;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.service.CutoverCheckService;
import com.experterp.backend.service.TaskImportService;
import com.experterp.backend.service.TaskImportService.Fulfilment;
import com.experterp.backend.service.TaskImportService.InvoiceStatus;
import com.experterp.backend.service.TaskImportService.Kind;
import com.experterp.backend.service.TaskImportService.Options;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review flow test F10, cutover tools (docs/review-and-test-plan.md, Part 3): Cin7's sale and purchase task files
 * imported, the go-live check, then a backup restored onto a copy the way infra/backup.sh and infra/restore.sh do it
 * (pg_dump -Fc, pg_restore --no-owner into a spare database). The figures must match the import files at each step.
 *
 * Before: 10 helmets at £6.00 in the warehouse.
 * Sale tasks: an open order SO-1 (3 helmets at £10.00, £30.00 to ship); an invoice INV-1 already shipped in Cin7
 * (2 helmets at £12.50 and £5.00 carriage = £30.00 net) with £10.00 paid.
 * Purchase tasks: an open order PO-7 (20 helmets at £5.00, £100.00 to receive); a bill 006387333 for 4 helmets at £5.00,
 * received, of which 2 credited (£10.00 net) and sent back.
 * After: 12 helmets worth £70.00; customers owe INV-1's total less £10.00; owed to suppliers the bill less its credit.
 */
class CutoverFlowTest extends FlowSupport {

    @Autowired TaskImportService imports;
    @Autowired CutoverCheckService cutover;
    @Autowired CustomerRepository customerRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired PostgreSQLContainer<?> postgres;

    @Test
    void cin7TasksImportedTheGoLiveCheckAgreesAndABackupRestoresOntoACopy() throws Exception {
        Product helmet = product("HELMET");
        in(helmet, main, 10, "6.00");
        Customer abbey = new Customer();
        abbey.setName("Abbey " + id);
        customerRepository.save(abbey);
        Supplier portwest = new Supplier();
        portwest.setName("Portwest " + id);
        supplierRepository.save(portwest);
        String day = today.minusDays(7).toString(), paid = today.minusDays(2).toString(), sku = helmet.getSku();

        // ---- sale tasks: an open order, then an invoice shipped in Cin7 with a part payment ----
        String openOrders = """
                RecordType*,CustomerName*,InvoiceNumber*,Product*,Quantity*,Price/Amount*,Total*,InvoiceDate*/ExpireDate,TaxInclusive*
                Invoice,Abbey %1$s,SO-1%1$s,,,,,%2$s,FALSE
                InvoiceLines,Abbey %1$s,SO-1%1$s,%3$s,3,10.00,30.00,,
                """.formatted(id, day, sku);
        TaskImportService.Result so = imports.importCsv(Kind.SALES, in(openOrders), "SaleTasks.csv",
                new Options(InvoiceStatus.NONE, Fulfilment.NO_PICKING, false, false, false, true, main.getId()), true);
        assertThat(so.errors()).isEmpty();
        assertThat(so.total()).isEqualByComparingTo("36.00");                                     // £30.00 net + 20 % VAT
        String invoices = """
                RecordType*,CustomerName*,InvoiceNumber*,Product*,Quantity*,Price/Amount*,Total*,Account,DatePaid*,InvoiceDate*/ExpireDate,InvoiceDueDate,TaxInclusive*
                Invoice,Abbey %1$s,INV-1%1$s,,,,,,,%2$s,%3$s,no
                InvoiceLines,Abbey %1$s,INV-1%1$s,%4$s,2,12.50,25.00,,,,,
                InvoiceAdditionalCharges,Abbey %1$s,INV-1%1$s,Carriage,1,5.00,5.00,,,,,
                Payment,Abbey %1$s,INV-1%1$s,,,10.00,,650,%5$s,,,
                """.formatted(id, day, today.plusDays(23), sku, paid);
        TaskImportService.Result inv = imports.importCsv(Kind.SALES, in(invoices), "SaleInvoices.csv",
                new Options(InvoiceStatus.AUTHORISED, Fulfilment.ALREADY_SHIPPED, false, false, false, false, main.getId()), true);
        assertThat(inv.errors()).isEmpty();
        assertThat(inv.paid()).isEqualByComparingTo("10.00");
        Map<String, Object> invoice = jdbc.queryForMap("SELECT subtotal, tax_total, total_amount, issue_date FROM invoices WHERE tenant_id = ? AND invoice_number = ?",
                tenantId, "INV-1" + id);
        assertThat((BigDecimal) invoice.get("subtotal")).isEqualByComparingTo("30.00");             // 2 × 12.50 + 5.00
        BigDecimal invoiceTotal = (BigDecimal) invoice.get("total_amount");
        assertThat(invoiceTotal).isEqualByComparingTo(((BigDecimal) invoice.get("subtotal")).add((BigDecimal) invoice.get("tax_total")));
        assertThat(invoice.get("issue_date")).hasToString(day);                                     // Cin7's own date

        // ---- purchase tasks: an open order, then a bill received with a credit and goods sent back ----
        String purchaseOrders = """
                RecordType*,SupplierName*,InvoiceNumber*,Product*,Quantity*,Price/Amount*,Total*,InvoiceDate*/ExpireDate,TaxInclusive*
                Invoice,Portwest %1$s,PO-7%1$s,,,,,%2$s,false
                InvoiceLines,Portwest %1$s,PO-7%1$s,%3$s,20,5.00,100.00,,
                """.formatted(id, day, sku);
        assertThat(imports.importCsv(Kind.PURCHASES, in(purchaseOrders), "PurchaseTasks.csv",
                new Options(InvoiceStatus.NONE, null, false, false, false, true, null), true).errors()).isEmpty();
        String bills = """
                RecordType*,SupplierName*,InvoiceNumber*,OrderNumber,Product*,Quantity*,Price/Amount*,Total*,DatePaid/DateReceived*,InvoiceDate*/ExpireDate,InvoiceDueDate,TaxInclusive*,CreditNoteNumber^,CreditNoteDate^
                Invoice,Portwest %1$s,006387333,PO-6%1$s,,,,,,%2$s,%2$s,false,,
                InvoiceLines,Portwest %1$s,006387333,,%4$s,4,5.00,20.00,,,,,,
                Received,Portwest %1$s,006387333,,%4$s,4,,,%2$s,,,,,
                CreditLines,Portwest %1$s,006387333,,%4$s,2,5.00,10.00,,,,,SC-1%1$s,%3$s
                Unstock,Portwest %1$s,006387333,,%4$s,2,,,%3$s,,,,,
                """.formatted(id, day, paid, sku);
        TaskImportService.Result bill = imports.importCsv(Kind.PURCHASES, in(bills), "Bills.csv",
                new Options(InvoiceStatus.AUTHORISED, null, false, false, false, true, main.getId()), true);
        assertThat(bill.errors()).isEmpty();
        BigDecimal billTotal = jdbc.queryForObject("SELECT total_amount FROM invoices WHERE tenant_id = ? AND invoice_number = '006387333'", BigDecimal.class, tenantId);
        BigDecimal creditTotal = jdbc.queryForObject("SELECT total_amount FROM invoices WHERE tenant_id = ? AND invoice_number = ?", BigDecimal.class, tenantId, "SC-1" + id);
        assertThat(jdbc.queryForObject("SELECT subtotal FROM invoices WHERE tenant_id = ? AND invoice_number = ?", BigDecimal.class, tenantId, "SC-1" + id))
                .isEqualByComparingTo("10.00");
        assertThat(imports.logs()).hasSize(4);

        // ==== the go-live check: every figure from the files ====
        CutoverCheckService.Check check = cutover.check();
        assertThat(figure(check, "units-" + main.getId())).isEqualByComparingTo("12");              // 10 + 4 received − 2 sent back
        assertThat(figure(check, "value-" + main.getId())).isEqualByComparingTo("70.00");           // 10 × 6.00 + 2 × 5.00
        assertThat(figure(check, "open-sales")).isEqualByComparingTo("1");
        assertThat(figure(check, "open-sales-value")).isEqualByComparingTo("30.00");
        assertThat(figure(check, "receivables")).isEqualByComparingTo(invoiceTotal.subtract(new BigDecimal("10.00")));
        assertThat(figure(check, "open-purchases")).isEqualByComparingTo("1");
        assertThat(figure(check, "open-purchases-value")).isEqualByComparingTo("100.00");
        assertThat(figure(check, "payables")).isEqualByComparingTo(billTotal.subtract(creditTotal));
        assertThat(value(helmet)).isEqualByComparingTo("70.00");
        assertThat(layersValue()).isEqualByComparingTo("70.00");

        // ==== backup and restore onto a copy, as infra/backup.sh and infra/restore.sh ====
        Map<String, String> live;
        try (Connection db = jdbcFor(postgres.getDatabaseName())) {
            live = fingerprint(db);
        }
        run("pg_dump", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(), "-Fc", "-f", "/tmp/f10.dump");
        run("pg_restore", "-l", "/tmp/f10.dump");                                                  // the backup reads back
        run("psql", "-U", postgres.getUsername(), "-d", "postgres", "-c", "DROP DATABASE IF EXISTS f10_restore WITH (FORCE)",
                "-c", "CREATE DATABASE f10_restore OWNER " + postgres.getUsername());
        run("pg_restore", "-U", postgres.getUsername(), "-d", "f10_restore", "--no-owner", "/tmp/f10.dump");
        try (Connection copy = jdbcFor("f10_restore")) {
            assertThat(fingerprint(copy)).as("the organisation's rows in the restored copy").isEqualTo(live);
            // The figures from the files, read from the copy
            assertThat(one(copy, "SELECT subtotal FROM invoices WHERE tenant_id = " + tenantId + " AND invoice_number = 'INV-1" + id + "'"))
                    .isEqualByComparingTo("30.00");
            assertThat(one(copy, "SELECT COALESCE(SUM(p.amount), 0) FROM payments p JOIN invoices i ON i.id = p.invoice_id WHERE p.tenant_id = " + tenantId
                    + " AND i.invoice_number = 'INV-1" + id + "'")).isEqualByComparingTo("10.00");
            // the supplier credit (£10.00 + VAT) is applied to the bill as a payment
            assertThat(one(copy, "SELECT COALESCE(SUM(p.amount), 0) FROM payments p JOIN invoices i ON i.id = p.invoice_id WHERE p.tenant_id = " + tenantId
                    + " AND i.invoice_number = '006387333'")).isEqualByComparingTo(creditTotal);
            assertThat(one(copy, "SELECT ROUND(SUM(remaining * unit_cost), 2) FROM cost_layers WHERE tenant_id = " + tenantId)).isEqualByComparingTo("70.00");
            assertThat(one(copy, "SELECT COUNT(*) FROM sale_orders WHERE tenant_id = " + tenantId)).isEqualByComparingTo("2");
            assertThat(one(copy, "SELECT COUNT(*) FROM purchase_orders WHERE tenant_id = " + tenantId)).isEqualByComparingTo("2");
            assertThat(one(copy, "SELECT COUNT(*) FROM flyway_schema_history WHERE success")).isEqualByComparingTo(
                    jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success", BigDecimal.class));
        } finally {
            run("psql", "-U", postgres.getUsername(), "-d", "postgres", "-c", "DROP DATABASE IF EXISTS f10_restore WITH (FORCE)");
        }
        assertThat(auditActions()).contains("DATA_IMPORT:SALES", "DATA_IMPORT:PURCHASES");
    }

    private void run(String... command) throws Exception {
        Container.ExecResult r = postgres.execInContainer(command);
        assertThat(r.getExitCode()).as(String.join(" ", command) + ": " + r.getStderr()).isZero();
    }

    private Connection jdbcFor(String database) throws Exception {
        String url = postgres.getJdbcUrl().replaceFirst("/" + postgres.getDatabaseName() + "(\\?|$)", "/" + database + "$1");
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    /** This organisation's rows, one hash per table that has an organisation (as F9). */
    private Map<String, String> fingerprint(Connection c) throws Exception {
        Map<String, String> out = new TreeMap<>();
        List<String> tables = jdbc.queryForList("""
                SELECT c.table_name FROM information_schema.columns c JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
                 WHERE c.table_schema = current_schema() AND c.column_name = 'tenant_id'
                   AND EXISTS (SELECT 1 FROM information_schema.columns i WHERE i.table_schema = c.table_schema AND i.table_name = c.table_name AND i.column_name = 'id')
                 ORDER BY 1""", String.class);
        try (Statement st = c.createStatement()) {
            for (String table : tables) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(t::text, '|' ORDER BY t.id)), '') FROM " + table
                        + " t WHERE t.tenant_id = " + tenantId)) {
                    rs.next();
                    out.put(table, rs.getString(1));
                }
            }
        }
        return out;
    }

    private static BigDecimal one(Connection c, String sql) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    private static ByteArrayInputStream in(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static BigDecimal figure(CutoverCheckService.Check c, String key) {
        return c.figures().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow().value();
    }
}
