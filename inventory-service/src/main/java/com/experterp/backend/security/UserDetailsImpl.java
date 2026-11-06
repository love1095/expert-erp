package com.experterp.backend.security;

import java.util.ArrayList;
import java.util.Set;

import com.experterp.backend.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.Collection;
import java.util.List;

@Getter
public class UserDetailsImpl implements UserDetails {

    private final Long id;
    private final String username;
    private final String password;
    private final User.Role role;
    private final Long tenantId;
    private final boolean active;
    private final Set<Permission> permissions;

    public UserDetailsImpl(User user) {
        this(user, null);
    }

    /** {@code rolePermissions}: the user's access role's ticks, or null to use the defaults of the user's role. */
    public UserDetailsImpl(User user, Set<Permission> rolePermissions) {
        this.id = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.role = user.getRole();
        this.tenantId = user.getTenantId();
        this.active = user.isActive();
        boolean admin = user.getRole() == User.Role.ADMIN || user.getRole() == User.Role.SUPER_ADMIN;
        this.permissions = admin || rolePermissions == null ? Permission.defaultsFor(user.getRole()) : rolePermissions;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        List<GrantedAuthority> a = new ArrayList<>();
        a.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        permissions.forEach(p -> a.add(new SimpleGrantedAuthority(p.authority())));
        return a;
    }

    @Override public boolean isAccountNonExpired()     { return true; }
    @Override public boolean isAccountNonLocked()      { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled()               { return active; }
}
