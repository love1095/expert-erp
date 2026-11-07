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
                .map(u -> new UserDetailsImpl(u, rolePermissions(u.getId())))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    // The ticks of all the user's roles together (Q5); none = the old role's defaults. Read without the organisation
    // filter: this runs before the organisation is known
    private java.util.Set<Permission> rolePermissions(Long userId) {
        java.util.List<String> p = jdbc.queryForList("""
                SELECT r.permissions FROM user_access_roles ur JOIN access_roles r ON r.id = ur.access_role_id WHERE ur.user_id = ?""", String.class, userId);
        if (p.isEmpty()) return null;
        java.util.Set<Permission> all = java.util.EnumSet.noneOf(Permission.class);
        p.forEach(x -> all.addAll(Permission.parse(x)));
        return all;
    }
}
