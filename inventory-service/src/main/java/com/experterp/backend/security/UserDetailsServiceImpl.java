package com.experterp.backend.security;

import com.experterp.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByUsername(username)
                .map(u -> new UserDetailsImpl(u, rolePermissions(u.getAccessRoleId())))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    // The access role's ticks (read without the organisation filter: this runs before the organisation is known)
    private java.util.Set<Permission> rolePermissions(Long accessRoleId) {
        if (accessRoleId == null) return null;
        java.util.List<String> p = jdbc.queryForList("SELECT permissions FROM access_roles WHERE id = ?", String.class, accessRoleId);
        return p.isEmpty() ? null : Permission.parse(p.get(0));
    }
}
