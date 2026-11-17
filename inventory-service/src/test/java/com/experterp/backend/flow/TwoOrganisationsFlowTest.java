package com.experterp.backend.flow;

import com.experterp.backend.dto.BillRequest;
import com.experterp.backend.dto.CreditNoteRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.ReorderRequest;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.entity.User;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.security.JwtUtil;
import com.experterp.backend.security.UserDetailsServiceImpl;
import com.experterp.backend.service.BankService;
import com.experterp.backend.service.BinService;
import com.experterp.backend.service.CreditNoteService;
import com.experterp.backend.service.LedgerService;
import com.experterp.backend.service.PurchaseOrderService;
import com.experterp.backend.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Review flow test F9, two organisations (docs/review-and-test-plan.md, Part 3): organisation A trades (a sale from
 * order to credit note, a purchase from order to payment, stock counted in, bins, an adjustment, a manual journal, a
 * bank transaction, customer credit); every name carries A's own marker and A's stock an odd quantity. Then B's admin
 * calls every endpoint of the API: the lists and reports over all dates and searching for A's marker, every address
 * with an id filled in with each of A's ids (from every table that has an organisation), and every change and delete
 * aimed at A's ids. Nothing of A may come back, and A's rows must be exactly as they were.
 */
@AutoConfigureMockMvc
class TwoOrganisationsFlowTest extends FlowSupport {

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserDetailsServiceImpl userDetailsService;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired SubscriptionService subscriptionService;
    @Autowired CustomerRepository customerRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired PurchaseOrderService purchaseOrders;
    @Autowired CreditNoteService creditNotes;
    @Autowired BankService bankService;
    @Autowired BinService bins;

    /** A's stock: a quantity no other organisation has, to find figures that carry no name. */
    static final String ODD = "7731";

    @Test
    void organisationBSeesAndChangesNothingOfOrganisationA() throws Exception {
        String marker = "SECRET" + id.toUpperCase();
        Long a = tenantId;
        tradeAsA(marker);
        List<Long> aIds = idsOf(a);
        assertThat(aIds).isNotEmpty();                      // on a fresh database A's rows are 1, 2, 3 in most tables

        // ---- organisation B, its own admin ----
        Long b = newOrganisation("Flow B " + id, "flow-b-" + id);
        subscriptionService.assignFreePlan(b);
        User admin = new User();
        admin.setUsername("b-admin-" + id);
        admin.setEmail("b-admin-" + id + "@example.com");
        admin.setPasswordHash(passwordEncoder.encode("Pw-" + id));
        admin.setRole(User.Role.ADMIN);
        admin.setTenantId(b);
        userRepository.save(admin);
        String token = jwtUtil.generateToken(userDetailsService.loadUserByUsername(admin.getUsername()));
        TenantContext.clear();
        // Every other organisation's rows (A's and any other), to see that B changed none of them
        Map<String, String> before = fingerprintAllBut(b);

        Pattern leak = Pattern.compile(Pattern.quote(marker) + "|[:\\[,]\\s*\"?" + ODD + "(\\.0+)?\"?\\s*[,}\\]]");
        List<String> leaks = new ArrayList<>(), changed = new ArrayList<>();
        Set<String> errors = new java.util.TreeSet<>();
        int calls = 0;
        for (Endpoint e : endpoints()) {
            if (e.pattern().startsWith("/api/auth/") || e.pattern().startsWith("/api/webhooks/") || e.pattern().startsWith("/api/platform")
                    || e.pattern().startsWith("/api/tenants")) continue;
            List<String> paths = new ArrayList<>();
            if (!e.pattern().contains("{")) paths.add(e.pattern());
            else for (Long x : aIds) paths.add(fill(e.pattern(), x));
            for (String path : paths) {
                int status;
                String body;
                try {
                    MockHttpServletResponse res = call(e.method(), path, token, marker);
                    status = res.getStatus();
                    body = res.getContentAsString(StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    // An error the server would answer with 500 (MockMvc throws it): its message is checked too
                    status = 500;
                    body = String.valueOf(ex.getCause() != null ? ex.getCause() : ex);
                    errors.add(e.method() + " " + e.pattern() + ": " + body.substring(0, Math.min(160, body.length())));
                }
                calls++;
                Matcher m = leak.matcher(body);
                if (m.find()) leaks.add(e.method() + " " + path + " -> " + status + ": ..." + around(body, m.start()) + "...");
                if (!e.method().equals("GET") && e.pattern().contains("{") && status / 100 == 2) {
                    changed.add(e.method() + " " + path + " -> " + status);
                }
            }
        }
        System.out.println("F9: " + calls + " calls as organisation B");
        errors.forEach(x -> System.out.println("F9 500: " + x));
        assertThat(leaks).as("organisation A's data seen by organisation B").isEmpty();
        // A change answered 2xx may have hit one of B's own rows with the same id; what counts is that no other
        // organisation's row changed
        changed.forEach(x -> System.out.println("F9 2xx on an id of A's (B's own row of that id, or a leak): " + x));
        Map<String, String> after = fingerprintAllBut(b);
        assertThat(after.entrySet()).as("other organisations' rows changed by organisation B's calls (table=count:hash)")
                .filteredOn(en -> !en.getValue().equals(before.get(en.getKey()))).isEmpty();
        assertThat(errors).as("requests the server could only answer with an error (500)").isEmpty();
    }

    // ---- organisation A's trade ----

    private void tradeAsA(String marker) {
        main.setName("Warehouse " + marker);
        main = warehouseRepository.save(main);
        Supplier supplier = new Supplier();
        supplier.setName("Supplier " + marker);
        supplier = supplierRepository.save(supplier);
        Customer customer = new Customer();
        customer.setName("Customer " + marker);
        customer = customerRepository.save(customer);
        Product gloves = product("GLOVES");
        gloves.setName("Gloves " + marker);
        gloves.setDefaultSupplier(supplier);
        gloves.getDetails().setSupplierPrice(new BigDecimal("2.00"));
        gloves = productRepository.save(gloves);
        BinService.Bin bin = bins.saveBin(null, new BinService.BinRequest(main.getId(), "S-" + id, 10, null));
        bins.setDefaultBin(gloves.getId(), main.getId(), bin.id());
        in(gloves, main, Integer.parseInt(ODD), "3.17");

        // A sale from the order to a credit note
        var so = order("Customer", line(gloves, "10", "6.00"));
        so.setCustomerName(null);
        so.setCustomerId(customer.getId());
        so.setNotes("Order " + marker);
        Long orderId = saleOrderService.confirm(saleOrderService.create(so).getId()).getId();
        saleOrderService.fulfill(orderId, null);
        InvoiceResponse invoice = invoiceAll(orderId);
        pay(invoice.getId(), "30.00");
        CreditNoteRequest cr = new CreditNoteRequest();
        CreditNoteRequest.Line cl = new CreditNoteRequest.Line();
        cl.setInvoiceItemId(invoice.getItems().get(0).getId());
        cl.setQuantity(BigDecimal.ONE);
        cl.setRestock(true);
        cr.setLines(List.of(cl));
        cr.setReason("Credit " + marker);
        creditNotes.create(invoice.getId(), cr);
        salesLedger.receiveOnAccount(customer.getId(), new com.experterp.backend.service.SalesLedgerService.CreditRequest(new BigDecimal("12.00"),
                today, bank, null, "Ref " + marker, "Credit " + marker));

        // A purchase from the order to the payment
        ReorderRequest reorder = new ReorderRequest();
        reorder.setWarehouseId(main.getId());
        ReorderRequest.Line rl = new ReorderRequest.Line();
        rl.setProductId(gloves.getId());
        rl.setQuantity(new BigDecimal("20"));
        reorder.setLines(List.of(rl));
        Long poId = purchaseOrders.createFromReorder(reorder).get(0).getId();
        PurchaseOrderResponse po = purchaseOrders.markOrdered(poId);
        purchaseOrders.receive(poId);
        BillRequest bill = new BillRequest();
        bill.setSupplierInvoiceNumber("BILL-" + marker);
        BillRequest.Line bl = new BillRequest.Line();
        bl.setPurchaseOrderLineId(po.getLines().get(0).getId());
        bl.setQuantity(new BigDecimal("20"));
        bl.setUnitPrice(new BigDecimal("2.00"));
        bill.setLines(List.of(bl));
        pay(invoiceService.billPurchaseOrder(poId, bill).getId(), "48.00");

        // The books: a manual journal and a bank transaction
        ledger.saveManual(null, new LedgerService.ManualJournalRequest(today, "Journal " + marker, List.of(
                new LedgerService.ManualLine("445", "Power " + marker, new BigDecimal("50.00"), null),
                new LedgerService.ManualLine("805", "Power " + marker, null, new BigDecimal("50.00"))), true));
        bankService.createTransaction(new BankService.TransactionRequest("SPEND", today, "650", null, "Payee " + marker, "Ref " + marker, true,
                List.of(new BankService.CreateLine("461", "Paper " + marker, new BigDecimal("12.00"), "20% (VAT on Expenses)"))));
    }

    // ---- what organisation A has ----

    /** Ids of A's rows in every table with an organisation (a few per table), so each address gets A's own ids. */
    private List<Long> idsOf(Long tenant) {
        Set<Long> ids = new LinkedHashSet<>();
        for (String table : tenantTables()) {
            ids.addAll(jdbc.queryForList("SELECT id FROM " + table + " WHERE tenant_id = ? ORDER BY id LIMIT 3", Long.class, tenant));
        }
        return new ArrayList<>(ids);
    }

    /** Each table's rows of every organisation but this one, as one hash per table: any change shows. */
    private Map<String, String> fingerprintAllBut(Long tenant) {
        Map<String, String> out = new TreeMap<>();
        for (String table : tenantTables()) {
            out.put(table, jdbc.queryForObject("SELECT COUNT(*) || ':' || COALESCE(md5(string_agg(t::text, '|' ORDER BY t.id)), '') FROM " + table
                    + " t WHERE t.tenant_id IS DISTINCT FROM ?", String.class, tenant));
        }
        return out;
    }

    private List<String> tenantTables() {
        return jdbc.queryForList("""
                SELECT c.table_name FROM information_schema.columns c
                 WHERE c.table_schema = current_schema() AND c.column_name = 'tenant_id'
                   AND EXISTS (SELECT 1 FROM information_schema.columns i WHERE i.table_schema = c.table_schema AND i.table_name = c.table_name
                               AND i.column_name = 'id' AND i.data_type IN ('bigint', 'integer'))
                   AND EXISTS (SELECT 1 FROM information_schema.tables t WHERE t.table_schema = c.table_schema AND t.table_name = c.table_name
                               AND t.table_type = 'BASE TABLE')
                 ORDER BY c.table_name""", String.class);
    }

    // ---- the API, as the server maps it ----

    record Endpoint(String method, String pattern) {}

    private List<Endpoint> endpoints() {
        Map<String, Endpoint> out = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> m : mappings.getHandlerMethods().entrySet()) {
            Set<RequestMethod> methods = m.getKey().getMethodsCondition().getMethods();
            for (String p : m.getKey().getPatternValues()) {
                if (!p.startsWith("/api/")) continue;
                for (RequestMethod rm : methods.isEmpty() ? EnumSet.of(RequestMethod.GET) : methods) out.put(p + " " + rm, new Endpoint(rm.name(), p));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Ids ({id}, {orderId} …) get A's id; other parts (a kind, a code) a plain word. */
    private static String fill(String pattern, Long id) {
        return Pattern.compile("\\{([^}]+)}").matcher(pattern).replaceAll(r -> {
            String name = r.group(1).toLowerCase();
            return name.equals("id") || name.endsWith("id") ? String.valueOf(id) : "x";
        });
    }

    private MockHttpServletResponse call(String method, String path, String token, String marker) throws Exception {
        MockHttpServletRequestBuilder req = request(HttpMethod.valueOf(method), path).header("Authorization", "Bearer " + token)
                // Lists and reports over every date, searching for A's own marker
                .param("from", "2000-01-01").param("to", "2100-12-31").param("asAt", today.toString()).param("date", today.toString())
                .param("q", marker).param("size", "100");
        if (!method.equals("GET") && !method.equals("DELETE")) req.contentType(MediaType.APPLICATION_JSON).content("{}");
        return mvc.perform(req).andReturn().getResponse();
    }

    private static String around(String body, int at) {
        return body.substring(Math.max(0, at - 60), Math.min(body.length(), at + 60)).replace('\n', ' ');
    }
}
