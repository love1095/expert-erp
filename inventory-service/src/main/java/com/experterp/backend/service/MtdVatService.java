package com.experterp.backend.service;

import com.experterp.backend.integration.hmrc.HmrcClient;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.security.SecretCipher;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Phase K4: VAT returns filed, and Making Tax Digital — the VAT return (H3, now with spend / receive money) sent to
 * HMRC, as Xero does: connect the organisation's HMRC login (OAuth), see the VAT periods HMRC expects (obligations),
 * review a period's nine boxes and send them with the legal declaration. HMRC's receipt is kept with the boxes as
 * sent, and the books are locked to the period end. A return filed another way (e.g. from Xero before the switch) is
 * recorded here the same way. Every call to HMRC carries its fraud prevention headers (WEB_APP_VIA_SERVER).
 */
@Service
@RequiredArgsConstructor
public class MtdVatService {

    public static final String DECLARATION = "When I submit this VAT information I am making a legal declaration that the information is true "
            + "and complete. A false declaration can result in prosecution.";
    private static final DateTimeFormatter HMRC_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private final JdbcTemplate jdbc;
    private final HmrcClient hmrc;
    private final SecretCipher cipher;
    private final VatReturnService vatReturns;
    private final LedgerService ledger;
    private final LedgerPeriodService periods;
    private final AuditService auditService;
    @Value("${app.hmrc.vendor-public-ip:}")
    private String vendorPublicIp;
    @Value("${app.version:1.0}")
    private String version;

    private final SecureRandom random = new SecureRandom();

    // ---- connection ----

    public record Status(boolean configured, String vrn, boolean connected, LocalDateTime connectedAt, String connectedBy) {}

    @Transactional(readOnly = true)
    public Status status() {
        Map<String, Object> c = connection();
        return new Status(hmrc.configured(), c == null ? null : (String) c.get("vrn"), c != null && c.get("access_token") != null,
                c == null || c.get("connected_at") == null ? null : ((Timestamp) c.get("connected_at")).toLocalDateTime(),
                c == null ? null : (String) c.get("connected_by"));
    }

    /** The VAT registration number: 9 digits (GB in front is dropped). */
    @Transactional
    public Status setVrn(String vrn) {
        String v = vrn == null ? "" : vrn.replaceAll("\\s", "").toUpperCase(Locale.ROOT).replaceFirst("^GB", "");
        if (!v.matches("\\d{9}")) throw new IllegalArgumentException("A UK VAT number has 9 digits");
        jdbc.update("INSERT INTO hmrc_connections (tenant_id, vrn) VALUES (?, ?) ON CONFLICT (tenant_id) DO UPDATE SET vrn = EXCLUDED.vrn",
                TenantContext.get(), v);
        auditService.log("MTD", null, "VRN", Map.of("vrn", v));
        return status();
    }

    /** Where to send the user to grant the ERP access at HMRC; the state ties HMRC's answer to this organisation. */
    @Transactional
    public String connect() {
        requireConfigured();
        if (status().vrn() == null) throw new IllegalStateException("Enter the VAT number first");
        String state = HexFormat.of().formatHex(random.generateSeed(16));
        jdbc.update("UPDATE hmrc_connections SET oauth_state = ? WHERE tenant_id = ?", state, TenantContext.get());
        return hmrc.authorizeUrl(state);
    }

    /** HMRC sends the user back with a code: exchanged for the tokens, kept encrypted. */
    @Transactional
    public Status callback(String code, String state) {
        Map<String, Object> c = connection();
        if (c == null || state == null || !state.equals(c.get("oauth_state"))) throw new IllegalStateException("This HMRC answer is not for this organisation: connect again");
        HmrcClient.Tokens tk = hmrc.exchangeCode(code);
        saveTokens(tk);
        jdbc.update("UPDATE hmrc_connections SET oauth_state = NULL, connected_at = now(), connected_by = ? WHERE tenant_id = ?",
                auditService.currentActorName(), TenantContext.get());
        auditService.log("MTD", null, "CONNECT", Map.of("vrn", String.valueOf(c.get("vrn"))));
        return status();
    }

    @Transactional
    public Status disconnect() {
        jdbc.update("UPDATE hmrc_connections SET access_token = NULL, refresh_token = NULL, expires_at = NULL, connected_at = NULL WHERE tenant_id = ?",
                TenantContext.get());
        auditService.log("MTD", null, "DISCONNECT", Map.of());
        return status();
    }

    // ---- periods, review, submit ----

    /** What the browser tells about itself, for HMRC's fraud prevention headers. */
    public record ClientInfo(String userAgent, String deviceId, Boolean doNotTrack, Integer screenWidth, Integer screenHeight, Double scalingFactor,
                             Integer colourDepth, Integer windowWidth, Integer windowHeight, String timezone, String publicIp, Integer publicPort) {}

    public record Period(String periodKey, LocalDate start, LocalDate end, LocalDate due, String status, Long filedReturnId) {}

    /** HMRC's VAT periods (open and fulfilled) for the dates, with the returns filed here. */
    @Transactional
    public List<Period> obligations(LocalDate from, LocalDate to, ClientInfo client) {
        Map<String, Object> c = requireConnected();
        LocalDate f = from == null ? LocalDate.now().minusYears(1) : from, t = to == null ? LocalDate.now().plusMonths(3) : to;
        List<HmrcClient.Obligation> list = hmrc.obligations((String) c.get("vrn"), accessToken(c), f, t, fraudHeaders(client));
        Map<String, Long> filed = new HashMap<>();
        jdbc.query("SELECT id, period_from, period_to FROM vat_returns WHERE tenant_id = ?",
                rs -> { filed.put(rs.getDate(2) + "|" + rs.getDate(3), rs.getLong(1)); }, TenantContext.get());
        return list.stream().sorted(Comparator.comparing(HmrcClient.Obligation::start).reversed())
                .map(o -> new Period(o.periodKey(), o.start(), o.end(), o.due(), o.status(), filed.get(o.start() + "|" + o.end()))).toList();
    }

    public record Review(VatReturnService.VatReturn vatReturn, BigDecimal ledgerVatMovement, List<String> warnings, String declaration) {}

    /** The period's nine boxes, and how the VAT account moved in the ledger in the same period (a check). */
    @Transactional
    public Review review(LocalDate from, LocalDate to) {
        VatReturnService.VatReturn r = vatReturns.vatReturn(from, to);
        ledger.sync(false);
        String vat = ledger.settings().vat();
        BigDecimal movement = Optional.ofNullable(jdbc.queryForObject("""
                SELECT SUM(l.amount) FROM journal_lines l JOIN journals j ON j.id = l.journal_id
                 WHERE j.tenant_id = ? AND lower(l.account_code) = lower(?) AND j.journal_date BETWEEN ? AND ? AND j.source_type <> 'OPENING'""",
                BigDecimal.class, TenantContext.get(), vat, Date.valueOf(from), Date.valueOf(to))).orElse(BigDecimal.ZERO).negate().setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = r.boxes().box3().subtract(r.boxes().box4());
        List<String> warnings = new ArrayList<>();
        if (movement.compareTo(net) != 0) {
            warnings.add("The VAT account (" + vat + ") moved " + movement.toPlainString() + " in the period, the return's VAT due less reclaimed is "
                    + net.toPlainString() + ": look for manual journals or VAT payments posted to " + vat);
        }
        Long filed = jdbc.query("SELECT id FROM vat_returns WHERE tenant_id = ? AND period_from = ? AND period_to = ?",
                rs -> rs.next() ? rs.getLong(1) : null, TenantContext.get(), Date.valueOf(from), Date.valueOf(to));
        if (filed != null) warnings.add("A return for this period is filed already");
        LocalDate lock = periods.periods().lockDate();
        if (to.isAfter(LocalDate.now())) warnings.add("The period has not ended yet");
        if (lock != null && !lock.isBefore(to)) warnings.add("The books are locked for this period");
        return new Review(r, movement, warnings, DECLARATION);
    }

    public record Filed(Long id, String periodKey, LocalDate from, LocalDate to, LocalDate due, VatReturnService.Boxes boxes, String how,
                        LocalDateTime filedAt, String filedBy, String processingDate, String formBundleNumber, String paymentIndicator,
                        String chargeRefNumber, String note) {}

    /** Sends the period's return to HMRC with the legal declaration; keeps HMRC's receipt and locks the books to the period end. */
    @Transactional
    public Filed submit(String periodKey, LocalDate from, LocalDate to, LocalDate due, boolean declaration, ClientInfo client) {
        requireAdviser();
        if (!declaration) throw new IllegalArgumentException("Tick the declaration to submit the return");
        if (periodKey == null || periodKey.isBlank()) throw new IllegalArgumentException("Choose the VAT period");
        if (to.isAfter(LocalDate.now())) throw new IllegalStateException("The period has not ended yet");
        requireNotFiled(from, to);
        Map<String, Object> c = requireConnected();
        VatReturnService.Boxes b = vatReturns.vatReturn(from, to).boxes();
        HmrcClient.Receipt receipt;
        try {
            receipt = hmrc.submit((String) c.get("vrn"), accessToken(c), new HmrcClient.Submission(periodKey, b.box1(), b.box2(), b.box3(), b.box4(),
                    b.box5(), b.box6().longValue(), b.box7().longValue(), b.box8().longValue(), b.box9().longValue(), true), fraudHeaders(client));
        } catch (HmrcClient.HmrcException e) {
            auditService.log("MTD", null, "SUBMIT_REFUSED", Map.of("periodKey", periodKey, "code", String.valueOf(e.code()), "message", String.valueOf(e.getMessage())));
            throw new IllegalStateException("HMRC refused the return: " + e.getMessage() + " (" + e.code() + ")");
        }
        Long id = save(periodKey, from, to, due, b, "MTD", receipt, null);
        auditService.log("MTD", id, "SUBMIT", Map.of("periodKey", periodKey, "box5", b.box5(), "formBundleNumber", String.valueOf(receipt.formBundleNumber())));
        return filed().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    /** A return filed another way (another package, HMRC's own service): recorded with its boxes, and the books locked. */
    @Transactional
    public Filed recordManual(LocalDate from, LocalDate to, String note) {
        requireAdviser();
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Give the VAT period (from – to)");
        if (to.isAfter(LocalDate.now())) throw new IllegalStateException("The period has not ended yet");
        requireNotFiled(from, to);
        VatReturnService.Boxes b = vatReturns.vatReturn(from, to).boxes();
        Long id = save(null, from, to, null, b, "MANUAL", null, note);
        auditService.log("MTD", id, "RECORD_FILED", Map.of("from", from.toString(), "to", to.toString(), "box5", b.box5()));
        return filed().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<Filed> filed() {
        return jdbc.query("SELECT * FROM vat_returns WHERE tenant_id = ? ORDER BY period_to DESC", (rs, n) -> new Filed(rs.getLong("id"),
                rs.getString("period_key"), rs.getDate("period_from").toLocalDate(), rs.getDate("period_to").toLocalDate(),
                rs.getDate("due_date") == null ? null : rs.getDate("due_date").toLocalDate(),
                new VatReturnService.Boxes(rs.getBigDecimal("box1"), rs.getBigDecimal("box2"), rs.getBigDecimal("box3"), rs.getBigDecimal("box4"),
                        rs.getBigDecimal("box5"), rs.getBigDecimal("box6"), rs.getBigDecimal("box7"), rs.getBigDecimal("box8"), rs.getBigDecimal("box9")),
                rs.getString("how"), rs.getTimestamp("filed_at").toLocalDateTime(), rs.getString("filed_by"),
                rs.getTimestamp("processing_date") == null ? null : rs.getTimestamp("processing_date").toLocalDateTime().toString(),
                rs.getString("form_bundle_number"), rs.getString("payment_indicator"), rs.getString("charge_ref_number"), rs.getString("note")),
                TenantContext.get());
    }

    private Long save(String periodKey, LocalDate from, LocalDate to, LocalDate due, VatReturnService.Boxes b, String how, HmrcClient.Receipt r, String note) {
        Timestamp processed = null;
        if (r != null && r.processingDate() != null) {
            try {
                processed = Timestamp.from(OffsetDateTime.parse(r.processingDate()).toInstant());
            } catch (RuntimeException e) {
                processed = Timestamp.valueOf(LocalDateTime.now());
            }
        }
        Long id = jdbc.queryForObject("""
                INSERT INTO vat_returns (tenant_id, period_key, period_from, period_to, due_date, box1, box2, box3, box4, box5, box6, box7, box8, box9,
                                         how, filed_by, processing_date, form_bundle_number, payment_indicator, charge_ref_number, note)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class,
                TenantContext.get(), periodKey, Date.valueOf(from), Date.valueOf(to), due == null ? null : Date.valueOf(due),
                b.box1(), b.box2(), b.box3(), b.box4(), b.box5(), b.box6(), b.box7(), b.box8(), b.box9(), how, auditService.currentActorName(),
                processed, r == null ? null : r.formBundleNumber(), r == null ? null : r.paymentIndicator(), r == null ? null : r.chargeRefNumber(),
                note == null || note.isBlank() ? null : note.trim());
        // Xero locks the period once its VAT return is filed: nothing in it changes the filed figures afterwards
        LedgerPeriodService.Periods p = periods.periods();
        if (p.lockDate() == null || p.lockDate().isBefore(to)) {
            periods.update(new LedgerPeriodService.Periods(p.yearEndDay(), p.yearEndMonth(), to, p.hardLockDate()));
        }
        return id;
    }

    private void requireNotFiled(LocalDate from, LocalDate to) {
        Boolean overlap = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM vat_returns WHERE tenant_id = ? AND period_from <= ? AND period_to >= ?)",
                Boolean.class, TenantContext.get(), Date.valueOf(to), Date.valueOf(from));
        if (Boolean.TRUE.equals(overlap)) throw new IllegalStateException("A VAT return covering these dates is filed already");
    }

    // ---- HMRC tokens and headers ----

    private Map<String, Object> connection() {
        List<Map<String, Object>> c = jdbc.queryForList("SELECT * FROM hmrc_connections WHERE tenant_id = ?", TenantContext.get());
        return c.isEmpty() ? null : c.get(0);
    }

    private Map<String, Object> requireConnected() {
        requireConfigured();
        Map<String, Object> c = connection();
        if (c == null || c.get("vrn") == null) throw new IllegalStateException("Enter the VAT number first");
        if (c.get("access_token") == null) throw new IllegalStateException("Connect to HMRC first");
        return c;
    }

    private void requireConfigured() {
        if (!hmrc.configured()) {
            throw new IllegalStateException("Making Tax Digital is not set up on this server: it needs the HMRC developer credentials "
                    + "(APP_HMRC_CLIENT_ID, APP_HMRC_CLIENT_SECRET, APP_HMRC_REDIRECT_URI)");
        }
    }

    // HMRC's access tokens last 4 hours: refreshed when (nearly) expired
    private String accessToken(Map<String, Object> c) {
        Timestamp exp = (Timestamp) c.get("expires_at");
        if (exp != null && exp.toLocalDateTime().isAfter(LocalDateTime.now().plusMinutes(2))) return cipher.decrypt((String) c.get("access_token"));
        if (c.get("refresh_token") == null) throw new IllegalStateException("The HMRC connection has expired: connect again");
        try {
            HmrcClient.Tokens tk = hmrc.refresh(cipher.decrypt((String) c.get("refresh_token")));
            saveTokens(tk);
            return tk.accessToken();
        } catch (HmrcClient.HmrcException e) {
            throw new IllegalStateException("The HMRC connection has expired (it lasts 18 months): connect again");
        }
    }

    private void saveTokens(HmrcClient.Tokens tk) {
        jdbc.update("UPDATE hmrc_connections SET access_token = ?, refresh_token = COALESCE(?, refresh_token), expires_at = ? WHERE tenant_id = ?",
                cipher.encrypt(tk.accessToken()), tk.refreshToken() == null ? null : cipher.encrypt(tk.refreshToken()),
                Timestamp.valueOf(LocalDateTime.now().plusSeconds(tk.expiresInSeconds())), TenantContext.get());
    }

    /** HMRC's fraud prevention headers for a web app reached through our server (WEB_APP_VIA_SERVER). */
    Map<String, String> fraudHeaders(ClientInfo c) {
        ClientInfo ci = c == null ? new ClientInfo(null, null, null, null, null, null, null, null, null, null, null, null) : c;
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Gov-Client-Connection-Method", "WEB_APP_VIA_SERVER");
        h.put("Gov-Client-Browser-JS-User-Agent", ci.userAgent());
        h.put("Gov-Client-Browser-Do-Not-Track", String.valueOf(Boolean.TRUE.equals(ci.doNotTrack())));
        h.put("Gov-Client-Device-ID", ci.deviceId());
        h.put("Gov-Client-Public-IP", ci.publicIp());
        h.put("Gov-Client-Public-IP-Timestamp", HMRC_TIME.format(LocalDateTime.now(ZoneOffset.UTC)));
        h.put("Gov-Client-Public-Port", ci.publicPort() == null ? null : String.valueOf(ci.publicPort()));
        if (ci.screenWidth() != null) {
            h.put("Gov-Client-Screens", "width=" + ci.screenWidth() + "&height=" + ci.screenHeight() + "&scaling-factor="
                    + (ci.scalingFactor() == null ? 1 : ci.scalingFactor()) + "&colour-depth=" + (ci.colourDepth() == null ? 24 : ci.colourDepth()));
        }
        h.put("Gov-Client-Timezone", ci.timezone());
        if (ci.windowWidth() != null) h.put("Gov-Client-Window-Size", "width=" + ci.windowWidth() + "&height=" + ci.windowHeight());
        String user = auditService.currentActorName();
        h.put("Gov-Client-User-IDs", "expert-erp=" + enc(user));
        String mfa = multiFactor(user);
        if (mfa != null) h.put("Gov-Client-Multi-Factor", mfa);
        if (ci.publicIp() != null) h.put("Gov-Vendor-Forwarded", "by=" + enc(vendorPublicIp.isBlank() ? "unknown" : vendorPublicIp) + "&for=" + enc(ci.publicIp()));
        h.put("Gov-Vendor-Product-Name", enc("Expert ERP"));
        h.put("Gov-Vendor-Public-IP", vendorPublicIp.isBlank() ? null : vendorPublicIp);
        h.put("Gov-Vendor-Version", "expert-erp=" + enc(version));
        return h;
    }

    // Two-step sign-in (I5) used: TOTP, when the user turned it on, with a reference that does not reveal who
    private String multiFactor(String user) {
        List<Boolean> on = jdbc.queryForList("SELECT totp_enabled FROM users WHERE tenant_id = ? AND username = ?", Boolean.class, TenantContext.get(), user);
        if (on.isEmpty() || !Boolean.TRUE.equals(on.get(0))) return null;
        try {
            String ref = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((TenantContext.get() + ":" + user).getBytes(StandardCharsets.UTF_8)));
            return "type=TOTP&timestamp=" + enc(HMRC_TIME.format(LocalDateTime.now(ZoneOffset.UTC))) + "&unique-reference=" + ref;
        } catch (Exception e) {
            return null;
        }
    }

    private static void requireAdviser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean ok = auth != null && auth.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN").contains(a.getAuthority()));
        if (!ok) throw new org.springframework.security.access.AccessDeniedException("Only an admin can file a VAT return");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
