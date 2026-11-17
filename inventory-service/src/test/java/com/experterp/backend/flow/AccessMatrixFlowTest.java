package com.experterp.backend.flow;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.entity.User;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.TenantRepository;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.security.JwtUtil;
import com.experterp.backend.security.Permission;
import com.experterp.backend.security.PermissionFilter;
import com.experterp.backend.security.UserDetailsImpl;
import com.experterp.backend.security.UserDetailsServiceImpl;
import com.experterp.backend.service.AccessRoleService;
import com.experterp.backend.service.SubscriptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.expression.SecurityExpressionRoot;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Review flow test F8, people and access (docs/review-and-test-plan.md, Part 3): every API endpoint against every
 * role, as a full matrix and not samples. Roles: Admin, the starting roles (Manager, Staff, Accountant), a role with no
 * ticks, and a custom role for each single tick (the Adviser tick among them).
 *
 * A request is judged the way the server judges it, layer by layer: the URL rules (SecurityConfig: deletes and users
 * for admins), the permission rules (PermissionFilter), then the controller's @PreAuthorize. The matrix is kept in
 * docs/access-matrix.csv: any change to who may do what shows there and must be looked at (run with
 * -Daccess.matrix.write=true to write it again). Endpoints a role with no ticks may call are listed below with the
 * reason, and checked live: a GET a no-ticks user may call must not hand out business data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class AccessMatrixFlowTest {

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @Autowired UserDetailsServiceImpl userDetailsService;
    @Autowired UserRepository userRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired SubscriptionService subscriptionService;
    @Autowired AccessRoleService accessRoles;
    @Autowired PasswordEncoder passwordEncoder;

    static final Path MATRIX = Path.of("..", "docs", "access-matrix.csv");

    /**
     * Any signed-in user, on purpose (a role with no ticks gets these): the reason for each. Patterns are the
     * endpoint's own ("GET /api/x/{id}").
     */
    static final Map<String, String> ANY_USER = new LinkedHashMap<>();
    static {
        String own = "the signed-in user's own sign-in security (two-factor)";
        for (String k : List.of("GET /api/account/two-factor", "POST /api/account/two-factor/confirm", "POST /api/account/two-factor/disable",
                "POST /api/account/two-factor/recovery-codes", "POST /api/account/two-factor/start")) ANY_USER.put(k, own);
        String signIn = "signing in (no login yet) or the user's own details and permissions";
        for (String k : List.of("POST /api/auth/login", "POST /api/auth/login/two-factor", "POST /api/auth/login/two-factor/setup",
                "GET /api/auth/me", "POST /api/auth/register")) ANY_USER.put(k, signIn);
        ANY_USER.put("GET /api/health", "public: whether the app and its database answer, no data");
        ANY_USER.put("GET /api/plans", "the subscription plans on offer, no organisation data");
        ANY_USER.put("POST /api/webhooks/shopify", "no login: checked by Shopify's signature");
        ANY_USER.put("POST /api/webhooks/xero", "no login: checked by Xero's signature");
        String perDocument = "checked per document in MailService (sale, purchase, customer, credit control)";
        for (String k : List.of("POST /api/mail/preview", "POST /api/mail/send", "POST /api/mail/statements", "POST /api/mail/reminders")) ANY_USER.put(k, perDocument);
        ANY_USER.put("GET /api/mail/settings", "read by the send dialog (from address); the SMTP password is never returned");
        ANY_USER.put("PUT /api/mail/settings", "admins only, checked in MailService.updateSettings");
        ANY_USER.put("GET /api/mail/templates", "read by the send dialog");
        ANY_USER.put("PUT /api/mail/templates/{type}", "admins only, checked in MailService.saveTemplate");
        ANY_USER.put("GET /api/search", "results filtered by the user's permissions in SearchService");
        ANY_USER.put("GET /api/lists/payment-terms", "terms offered on order, customer and supplier forms");
        ANY_USER.put("GET /api/settings/company", "the company's name and address on screens and documents");
        ANY_USER.put("GET /api/settings/company/logo", "the company's logo on screens and documents");
        ANY_USER.put("GET /api/settings/document-templates", "template names for the print menu of any document");
        ANY_USER.put("GET /api/settings/organisation", "numbering and defaults read by the forms; changing them is admin only");
        ANY_USER.put("GET /api/settings/payment-accounts", "the accounts offered on payment forms");
        ANY_USER.put("GET /api/settings/price-tiers", "the price tiers offered on customer and product forms");
        ANY_USER.put("GET /api/subscription", "the organisation's plan and its limits");
        ANY_USER.put("GET /api/tax-rates", "the VAT rates offered on order lines");
        ANY_USER.put("GET /api/categories", "category names used by every product, stock and report filter");
        ANY_USER.put("GET /api/warehouses", "the warehouse list every order and stock form uses");
        ANY_USER.put("GET /api/warehouses/{id}", "the warehouse list every order and stock form uses");
    }

    record Endpoint(String method, String pattern, HandlerMethod handler) {
        String key() { return method + " " + pattern; }
        String samplePath() { return pattern.replaceAll("\\{[^}]+}", "999999").replaceAll("\\*\\*", "x"); }
    }

    record RoleDef(String name, String baseRole, Set<Permission> ticks) {}

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void everyEndpointAgainstEveryRoleAFullMatrix() throws Exception {
        List<Endpoint> endpoints = endpoints();
        List<RoleDef> roles = roles();
        StringBuilder csv = new StringBuilder("endpoint,").append(roles.stream().map(RoleDef::name).collect(Collectors.joining(","))).append('\n');
        List<String> openToAll = new ArrayList<>(), adminRefused = new ArrayList<>();
        for (Endpoint e : endpoints) {
            csv.append(e.key());
            for (RoleDef r : roles) {
                String d = decide(e, r);
                csv.append(',').append(d);
                if (r.name().equals("No ticks") && !d.startsWith("-")) openToAll.add(e.key());
                if (r.name().equals("Admin") && d.startsWith("-") && !e.pattern().startsWith("/api/tenants") && !e.pattern().startsWith("/api/platform")) adminRefused.add(e.key() + " " + d);
            }
            csv.append('\n');
        }
        System.out.println("ACCESS: " + endpoints.size() + " endpoints x " + roles.size() + " roles");
        openToAll.stream().filter(k -> !ANY_USER.containsKey(k)).forEach(k -> System.out.println("OPEN: " + k));

        assertThat(adminRefused).as("an admin is refused").isEmpty();
        // The Adviser tick opens everything the Accounting tick does (and the adviser's own jobs on top)
        RoleDef adviser = roles.stream().filter(r -> r.name().equals("ADVISER")).findFirst().orElseThrow();
        RoleDef accounting = roles.stream().filter(r -> r.name().equals("ACCOUNTING")).findFirst().orElseThrow();
        assertThat(endpoints).filteredOn(e -> !decide(e, accounting).startsWith("-")).allMatch(e -> !decide(e, adviser).startsWith("-"));
        assertThat(openToAll).as("open to a role with no ticks, not listed in ANY_USER with a reason").allMatch(ANY_USER::containsKey);
        assertThat(ANY_USER.keySet()).as("listed in ANY_USER but no longer open to everyone (or gone)").allMatch(openToAll::contains);
        // ---- live: the server refuses what the matrix says it refuses, and an admin is refused nothing ----
        String id = UUID.randomUUID().toString().substring(0, 8);
        Long tenantId = organisation(id);
        String admin = token(tenantId, "admin-" + id, User.Role.ADMIN, Set.of());
        String none = token(tenantId, "none-" + id, User.Role.STAFF, Set.of());
        RoleDef noTicks = roles.stream().filter(r -> r.name().equals("No ticks")).findFirst().orElseThrow();
        List<String> wrong = new ArrayList<>();
        for (Endpoint e : endpoints) {
            if (e.pattern().startsWith("/api/auth/") || e.pattern().startsWith("/api/webhooks/")) continue;
            String d = decide(e, noTicks);
            boolean bodyFirst = d.equals("-pre") && !e.method().equals("GET");      // a body is read before @PreAuthorize
            if (d.startsWith("-") && !bodyFirst) {
                int st = status(e, none);
                if (st != 403) wrong.add("no ticks " + e.key() + " -> " + st + " (matrix " + d + ")");
            } else if (!d.startsWith("-") && e.method().equals("GET")) {
                int st = status(e, none);
                if (st == 403) wrong.add("no ticks " + e.key() + " -> 403 (listed as open to every user)");
            }
            if (e.method().equals("GET") && !e.pattern().startsWith("/api/tenants") && !e.pattern().startsWith("/api/platform")) {
                int st = status(e, admin);
                if (st == 403) wrong.add("admin " + e.key() + " -> 403");
            }
        }
        assertThat(wrong).as("the server and the matrix disagree").isEmpty();

        if (Boolean.getBoolean("access.matrix.write") || !Files.exists(MATRIX)) Files.writeString(MATRIX, csv);
        assertThat(Files.readString(MATRIX)).as("docs/access-matrix.csv: who may do what changed; look at the difference, "
                + "then write it again with -Daccess.matrix.write=true").isEqualTo(csv.toString());
    }

    // ---- the endpoints, as the server maps them ----

    private List<Endpoint> endpoints() {
        Map<String, Endpoint> out = new TreeMap<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> m : mappings.getHandlerMethods().entrySet()) {
            Set<String> patterns = m.getKey().getPatternValues();
            Set<RequestMethod> methods = m.getKey().getMethodsCondition().getMethods();
            for (String p : patterns) {
                if (!p.startsWith("/api/")) continue;
                for (RequestMethod rm : methods.isEmpty() ? EnumSet.of(RequestMethod.GET) : methods) {
                    Endpoint e = new Endpoint(rm.name(), p, m.getValue());
                    out.put(e.pattern() + " " + e.method(), e);
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    // ---- the roles ----

    private static List<RoleDef> roles() {
        List<RoleDef> r = new ArrayList<>();
        r.add(new RoleDef("Admin", "ADMIN", Permission.defaultsFor(User.Role.ADMIN)));
        r.add(new RoleDef("Manager", "MANAGER", Permission.defaultsFor(User.Role.MANAGER)));
        r.add(new RoleDef("Staff", "STAFF", Permission.defaultsFor(User.Role.STAFF)));
        r.add(new RoleDef("Accountant", "STAFF", AccessRoleService.ACCOUNTANT));
        r.add(new RoleDef("No ticks", "STAFF", EnumSet.noneOf(Permission.class)));
        for (Permission p : Permission.values()) r.add(new RoleDef(p.name(), "STAFF", EnumSet.of(p)));
        return r;
    }

    /** The authorities the server gives a user of this base role with these ticks (UserDetailsImpl, as at sign-in). */
    private static Authentication auth(RoleDef r) {
        User u = new User();
        u.setUsername("matrix");
        u.setRole(User.Role.valueOf(r.baseRole()));
        u.setActive(true);
        return new UsernamePasswordAuthenticationToken("matrix", null, new UserDetailsImpl(u, r.ticks()).getAuthorities());
    }

    /**
     * "Y" allowed; "-url" refused by SecurityConfig, "-perm" by PermissionFilter, "-pre" by @PreAuthorize;
     * "Y?" allowed so far but the @PreAuthorize could not be judged without the call itself.
     */
    private static String decide(Endpoint e, RoleDef r) {
        Authentication auth = auth(r);
        Set<String> has = auth.getAuthorities().stream().map(Object::toString).collect(Collectors.toSet());
        String path = e.samplePath();
        boolean admin = has.contains("ROLE_ADMIN") || has.contains("ROLE_SUPER_ADMIN");
        if ((e.method().equals("DELETE") || path.startsWith("/api/users")) && !admin) return "-url";
        Set<Permission> need = PermissionFilter.required(e.method(), path);
        if (need != null && need.stream().noneMatch(p -> has.contains(p.authority()))) return "-perm";
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(e.handler().getMethod(), PreAuthorize.class);
        if (pre == null) pre = AnnotatedElementUtils.findMergedAnnotation(e.handler().getBeanType(), PreAuthorize.class);
        if (pre == null) return "Y";
        try {
            SecurityExpressionRoot root = new SecurityExpressionRoot(auth) {};
            Boolean ok = new SpelExpressionParser().parseExpression(pre.value()).getValue(new StandardEvaluationContext(root), Boolean.class);
            return Boolean.TRUE.equals(ok) ? "Y" : "-pre";
        } catch (RuntimeException ex) {
            return "Y?";
        }
    }

    // ---- live: real users with real tokens ----

    private String token(Long tenantId, String name, User.Role role, Set<Permission> ticks) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setPasswordHash(passwordEncoder.encode("Pw-" + name));
        u.setRole(role);
        u.setTenantId(tenantId);
        u = userRepository.save(u);
        if (role != User.Role.ADMIN) {
            AccessRoleService.Role ar = accessRoles.create(new AccessRoleService.RoleRequest(name, ticks.stream().map(Enum::name).toList()));
            accessRoles.assign(u.getId(), ar.id());
        }
        return jwtUtil.generateToken(userDetailsService.loadUserByUsername(name));
    }

    private Long organisation(String id) {
        Tenant t = new Tenant();
        t.setName("Access " + id);
        t.setSlug("access-" + id);
        Long tenantId = tenantRepository.save(t).getId();
        subscriptionService.assignFreePlan(tenantId);
        TenantContext.set(tenantId);
        return tenantId;
    }

    private int status(Endpoint e, String token) throws Exception {
        return mvc.perform(request(HttpMethod.valueOf(e.method()), e.samplePath()).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

}
