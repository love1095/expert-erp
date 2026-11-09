package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manage dashboard, like Cin7's: each user's choice of dashboard parts (shown or hidden, order, folded, full width),
 * else the organisation's default (set by an admin), else the standard layout (null: the page uses its own).
 * Only the layout is stored here; which parts a role may see is decided by the page and the server's permissions.
 */
@Service
@RequiredArgsConstructor
public class DashboardLayoutService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public record Part(String key, boolean visible, boolean collapsed, boolean wide) {}
    public record Layout(List<Part> parts) {}
    // source: USER (their own), ORGANISATION (the admin's default) or STANDARD (nothing saved)
    public record Result(Layout layout, String source, boolean organisationDefault) {}

    @Transactional(readOnly = true)
    public Result forUser(Long userId) {
        Layout own = load(userId);
        Layout org = load(null);
        if (own != null) return new Result(own, "USER", org != null);
        if (org != null) return new Result(org, "ORGANISATION", true);
        return new Result(null, "STANDARD", false);
    }

    @Transactional
    public Result save(Long userId, Layout layout) {
        store(userId, check(layout));
        return forUser(userId);
    }

    /** Back to the organisation's default (or the standard layout). */
    @Transactional
    public Result reset(Long userId) {
        jdbc.update("DELETE FROM dashboard_layouts WHERE tenant_id = ? AND user_id = ?", TenantContext.get(), userId);
        return forUser(userId);
    }

    /** The admin's default for everyone who has not chosen their own; null layout removes it. */
    @Transactional
    public void saveDefault(Layout layout) {
        if (layout == null) {
            jdbc.update("DELETE FROM dashboard_layouts WHERE tenant_id = ? AND user_id IS NULL", TenantContext.get());
            return;
        }
        store(null, check(layout));
    }

    private Layout check(Layout layout) {
        if (layout == null || layout.parts() == null || layout.parts().isEmpty())
            throw new IllegalArgumentException("The layout has no parts");
        if (layout.parts().size() > 50) throw new IllegalArgumentException("Too many dashboard parts");
        Set<String> seen = new HashSet<>();
        for (Part p : layout.parts()) {
            if (p.key() == null || !p.key().matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("Unknown dashboard part");
            if (!seen.add(p.key())) throw new IllegalArgumentException("A dashboard part is listed twice: " + p.key());
        }
        return layout;
    }

    private Layout load(Long userId) {
        List<String> rows = userId == null
                ? jdbc.queryForList("SELECT layout FROM dashboard_layouts WHERE tenant_id = ? AND user_id IS NULL", String.class, TenantContext.get())
                : jdbc.queryForList("SELECT layout FROM dashboard_layouts WHERE tenant_id = ? AND user_id = ?", String.class, TenantContext.get(), userId);
        if (rows.isEmpty()) return null;
        try {
            return json.readValue(rows.get(0), Layout.class);
        } catch (JsonProcessingException e) {
            return null;   // an unreadable layout falls back to the default
        }
    }

    private void store(Long userId, Layout layout) {
        String text;
        try {
            text = json.writeValueAsString(layout);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The layout could not be saved");
        }
        Long t = TenantContext.get();
        int n = userId == null
                ? jdbc.update("UPDATE dashboard_layouts SET layout = ?, updated_at = now() WHERE tenant_id = ? AND user_id IS NULL", text, t)
                : jdbc.update("UPDATE dashboard_layouts SET layout = ?, updated_at = now() WHERE tenant_id = ? AND user_id = ?", text, t, userId);
        if (n == 0) jdbc.update("INSERT INTO dashboard_layouts (tenant_id, user_id, layout) VALUES (?, ?, ?)", t, userId, text);
    }
}
