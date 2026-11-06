package com.experterp.backend.security;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Signs a request in with an Account ID and Application Key (headers api-auth-accountid and
 * api-auth-applicationkey, as in Cin7 Core's API). The request then works as that organisation, with the key's
 * role. Keys reach the business data only: signing in, users, the subscription, settings and integrations stay
 * with people who log in.
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    // Account administration: never with a key
    static final List<String> PEOPLE_ONLY = List.of("/api/auth/", "/api/users", "/api/subscription", "/api/settings/",
            "/api/tenants", "/api/integrations/", "/api/costing/");

    private final ApiKeyService apiKeyService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String account = request.getHeader(ApiKeyService.ACCOUNT_HEADER);
        String key = request.getHeader(ApiKeyService.KEY_HEADER);
        if (account == null && key == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiKeyPrincipal> principal = apiKeyService.authenticate(account, key);
        if (principal.isEmpty()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "Account ID or Application Key is wrong, or the key was revoked");
            return;
        }
        String path = request.getRequestURI();
        if (PEOPLE_ONLY.stream().anyMatch(path::startsWith)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "This part of the API needs a person to log in; an Application Key cannot use it");
            return;
        }
        ApiKeyPrincipal p = principal.get();
        TenantContext.set(p.tenantId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                p, null, authorities(p.role())));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    // An Application Key works with its role's default permissions (phase I1)
    private static List<SimpleGrantedAuthority> authorities(com.experterp.backend.entity.User.Role role) {
        List<SimpleGrantedAuthority> a = new java.util.ArrayList<>();
        a.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        Permission.defaultsFor(role).forEach(p -> a.add(new SimpleGrantedAuthority(p.authority())));
        return a;
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
