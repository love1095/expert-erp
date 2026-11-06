package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Phase K3: something reconciled with a bank statement line is not voided until the line is unreconciled (as Xero). */
@Component
@RequiredArgsConstructor
public class ReconciliationGuard {

    private final JdbcTemplate jdbc;

    /** sourceKey: the ledger source (PAY:12, MJ:3, BT:7, CC:2, SC:4). */
    public void requireNotReconciled(String sourceKey) {
        Boolean r = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bank_matches WHERE tenant_id = ? AND source_key = ?)", Boolean.class,
                TenantContext.get(), sourceKey);
        if (Boolean.TRUE.equals(r)) throw new IllegalStateException("It is reconciled with a bank statement line: unreconcile that line first");
    }
}
