package com.experterp.backend.service;

import com.experterp.backend.entity.User;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.UserRepository;
import com.experterp.backend.security.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Roles with ticked permissions, like Cin7 Core's Settings → Users &amp; Roles (phase I1). An organisation starts with
 * "Manager" and "Staff" roles holding the old roles' defaults; its admins add, change and remove roles and give each
 * user one. Admins themselves always have everything (Cin7: the owner).
 */
@Service
@RequiredArgsConstructor
public class AccessRoleService {

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public record PermissionInfo(String key, String group, String label) {}
    public record Role(Long id, String name, List<String> permissions, int users) {}
    public record RoleRequest(String name, List<String> permissions) {}

    public List<PermissionInfo> catalogue() {
        return Arrays.stream(Permission.values()).map(p -> new PermissionInfo(p.name(), p.group(), p.label())).toList();
    }

    @Transactional
    public List<Role> roles() {
        Long t = TenantContext.get();
        ensureDefaults(t);
        return jdbc.query("""
                SELECT r.id, r.name, r.permissions, (SELECT COUNT(*) FROM user_access_roles ur WHERE ur.access_role_id = r.id)
                FROM access_roles r WHERE r.tenant_id = ? ORDER BY lower(r.name)""", (rs, n) -> new Role(rs.getLong(1),
                rs.getString(2), Permission.parse(rs.getString(3)).stream().map(Enum::name).toList(), rs.getInt(4)), t);
    }

    // The roles every organisation starts with: the old Manager and Staff defaults, and (K5) the accountant
    private void ensureDefaults(Long t) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM access_roles WHERE tenant_id = ?", Integer.class, t);
        if (n != null && n > 0) return;
        jdbc.update("INSERT INTO access_roles (tenant_id, name, permissions) VALUES (?, 'Manager', ?), (?, 'Staff', ?), (?, 'Accountant', ?) ON CONFLICT DO NOTHING",
                t, csv(Permission.defaultsFor(User.Role.MANAGER)), t, csv(Permission.defaultsFor(User.Role.STAFF)), t, csv(ACCOUNTANT));
    }

    /** Xero's Adviser for the accountant: the books, the reports, everything else to look at only. */
    public static final java.util.Set<Permission> ACCOUNTANT = java.util.EnumSet.of(Permission.ACCOUNTING, Permission.ADVISER,
            Permission.REPORTS_FINANCIAL, Permission.REPORTS_SALE, Permission.REPORTS_PURCHASE, Permission.REPORTS_INVENTORY,
            Permission.SALE_VIEW, Permission.SALE_PROFIT, Permission.PURCHASE_VIEW, Permission.PURCHASE_PRICES, Permission.PRODUCT_VIEW,
            Permission.PRODUCT_COST, Permission.STOCK_VALUE, Permission.DASHBOARD, Permission.AUDIT_LOG);

    @Transactional
    public Role create(RoleRequest req) {
        Long t = TenantContext.get();
        String name = name(req);
        try {
            Long id = jdbc.queryForObject("INSERT INTO access_roles (tenant_id, name, permissions) VALUES (?, ?, ?) RETURNING id",
                    Long.class, t, name, csv(permissions(req)));
            auditService.log("ACCESS_ROLE", id, "CREATE", Map.of("name", name, "permissions", csv(permissions(req))));
            return find(id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("There is a role called " + name + " already");
        }
    }

    @Transactional
    public Role update(Long id, RoleRequest req) {
        Long t = TenantContext.get();
        Role before = find(id);
        String name = name(req);
        try {
            jdbc.update("UPDATE access_roles SET name = ?, permissions = ?, updated_at = now() WHERE tenant_id = ? AND id = ?",
                    name, csv(permissions(req)), t, id);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("There is a role called " + name + " already");
        }
        auditService.log("ACCESS_ROLE", id, "UPDATE", Map.of("name", name, "before", String.join(",", before.permissions()),
                "after", csv(permissions(req))));
        return find(id);
    }

    @Transactional
    public void delete(Long id) {
        Role r = find(id);
        if (r.users() > 0) throw new IllegalStateException(r.name() + " is given to " + r.users() + " user(s): give them another role first");
        jdbc.update("DELETE FROM access_roles WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        auditService.log("ACCESS_ROLE", id, "DELETE", Map.of("name", r.name()));
    }

    /** Gives a user of this organisation a role (null: the defaults of the user's Manager / Staff role). */
    @Transactional
    public void assign(Long userId, Long roleId) {
        assignAll(userId, roleId == null ? List.of() : List.of(roleId));
    }

    /** Q5: the user's roles (several: their permissions add up; none: the defaults of the user's Manager / Staff role). */
    @Transactional
    public void assignAll(Long userId, List<Long> roleIds) {
        User u = userRepository.findById(userId).filter(x -> Objects.equals(x.getTenantId(), TenantContext.get()))
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        List<Long> ids = roleIds == null ? List.of() : roleIds.stream().filter(Objects::nonNull).distinct().toList();
        List<String> names = ids.stream().map(id -> find(id).name()).toList();
        jdbc.update("DELETE FROM user_access_roles WHERE user_id = ?", userId);
        for (Long id : ids) jdbc.update("INSERT INTO user_access_roles (user_id, access_role_id, tenant_id) VALUES (?, ?, ?)", userId, id, TenantContext.get());
        u.setAccessRoleId(ids.isEmpty() ? null : ids.get(0));
        userRepository.save(u);
        auditService.log("USER", userId, "ACCESS_ROLE", Map.of("user", u.getUsername(), "roles", names.isEmpty() ? List.of("default") : names));
    }

    /** The user's roles, by id. */
    @Transactional(readOnly = true)
    public List<Long> rolesOf(Long userId) {
        return jdbc.queryForList("SELECT access_role_id FROM user_access_roles WHERE user_id = ? AND tenant_id = ? ORDER BY access_role_id", Long.class, userId, TenantContext.get());
    }

    private Role find(Long id) {
        return roles().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + id));
    }

    private static String name(RoleRequest req) {
        String name = req.name() == null ? "" : req.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Give the role a name");
        if (name.length() > 60) throw new IllegalArgumentException("A role name is at most 60 characters");
        return name;
    }

    private static Set<Permission> permissions(RoleRequest req) {
        Set<Permission> out = EnumSet.noneOf(Permission.class);
        for (String p : req.permissions() == null ? List.<String>of() : req.permissions()) {
            try { out.add(Permission.valueOf(p)); }
            catch (IllegalArgumentException e) { throw new IllegalArgumentException("Unknown permission: " + p); }
        }
        return out;
    }

    private static String csv(Set<Permission> p) {
        return p.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
