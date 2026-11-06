package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase K2: Xero's financial settings — the financial year end (retained earnings roll over by themselves at each
 * year end, no closing journal, as Xero) and the two lock dates: "for all users except advisers" and "for all users".
 * Nothing dated on or before the lock date can be entered, changed or voided; advisers (admins here, until accountant
 * access in K5) may still work up to the second date.
 */
@Service
@RequiredArgsConstructor
public class LedgerPeriodService {

    private static final DateTimeFormatter UK = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    /** yearEndDay / yearEndMonth: the last day of the financial year (31 March when not set). */
    public record Periods(int yearEndDay, int yearEndMonth, LocalDate lockDate, LocalDate hardLockDate) {}

    @Transactional(readOnly = true)
    public Periods periods() {
        List<Periods> p = jdbc.query("SELECT year_end_day, year_end_month, lock_date, hard_lock_date FROM accounting_settings WHERE tenant_id = ?",
                (rs, n) -> new Periods(rs.getObject(1) == null ? 31 : rs.getInt(1), rs.getObject(2) == null ? 3 : rs.getInt(2),
                        rs.getDate(3) == null ? null : rs.getDate(3).toLocalDate(), rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate()),
                TenantContext.get());
        return p.isEmpty() ? new Periods(31, 3, null, null) : p.get(0);
    }

    @Transactional
    public Periods update(Periods p) {
        try {
            MonthDay.of(p.yearEndMonth(), p.yearEndDay());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The financial year end is not a date");
        }
        if (p.yearEndMonth() == 2 && p.yearEndDay() == 29) throw new IllegalArgumentException("Use 28 February as the year end");
        if (p.lockDate() != null && p.lockDate().isAfter(LocalDate.now())) throw new IllegalArgumentException("A lock date cannot be in the future");
        if (p.hardLockDate() != null && p.hardLockDate().isAfter(LocalDate.now())) throw new IllegalArgumentException("A lock date cannot be in the future");
        if (p.hardLockDate() != null && p.lockDate() != null && p.hardLockDate().isAfter(p.lockDate())) {
            throw new IllegalArgumentException("The lock date for all users must be on or before the lock date for all users except advisers");
        }
        Long t = TenantContext.get();
        jdbc.update("INSERT INTO accounting_settings (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", t);
        jdbc.update("UPDATE accounting_settings SET year_end_day = ?, year_end_month = ?, lock_date = ?, hard_lock_date = ?, updated_at = now(), updated_by = ? WHERE tenant_id = ?",
                p.yearEndDay(), p.yearEndMonth(), d(p.lockDate()), d(p.hardLockDate()), auditService.currentActorName(), t);
        auditService.log("LEDGER", null, "PERIODS", Map.of("yearEnd", p.yearEndDay() + "/" + p.yearEndMonth(),
                "lockDate", String.valueOf(p.lockDate()), "hardLockDate", String.valueOf(p.hardLockDate())));
        return periods();
    }

    /** The first day of the financial year that holds this day. */
    public LocalDate yearStart(LocalDate day) {
        Periods p = periods();
        LocalDate end = yearEnd(day.getYear(), p);
        if (!day.isAfter(end)) end = yearEnd(day.getYear() - 1, p);
        return end.plusDays(1);
    }

    // A year end on the last day of a month stays on it (28 February → 29 February in a leap year), as Xero
    private static LocalDate yearEnd(int year, Periods p) {
        java.time.Month m = java.time.Month.of(p.yearEndMonth());
        int length = m.length(java.time.Year.isLeap(year));
        int day = p.yearEndDay() >= m.length(false) ? length : Math.min(p.yearEndDay(), length);
        return LocalDate.of(year, m, day);
    }

    /** The date nothing may be dated on or before, for whoever is signed in (advisers: only the "all users" date). */
    public LocalDate effectiveLock() {
        Periods p = periods();
        if (adviser()) return p.hardLockDate();
        if (p.lockDate() == null) return p.hardLockDate();
        if (p.hardLockDate() == null) return p.lockDate();
        return p.lockDate().isAfter(p.hardLockDate()) ? p.lockDate() : p.hardLockDate();
    }

    /** Refuses something dated in a locked period. */
    public void check(LocalDate date, String what) {
        if (date == null) return;
        LocalDate lock = effectiveLock();
        if (lock != null && !date.isAfter(lock)) {
            throw new IllegalStateException("The books are locked up to " + lock.format(UK) + ": " + what + " dated " + date.format(UK) + " cannot be entered or changed");
        }
    }

    private static boolean adviser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> Set.of("ROLE_ADMIN", "ROLE_SUPER_ADMIN", "PERM_ADVISER").contains(a.getAuthority()));
    }

    private static Date d(LocalDate v) {
        return v == null ? null : Date.valueOf(v);
    }
}
