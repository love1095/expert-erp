package com.experterp.backend.security;

import com.experterp.backend.entity.User;

/**
 * Who is calling when a request signs in with an Account ID and Application Key: the key, for one organisation,
 * with the role it was given. Audit entries name the key ("API: Warehouse scanner").
 */
public record ApiKeyPrincipal(Long keyId, Long tenantId, String keyName, User.Role role) {

    @Override
    public String toString() {
        return "API: " + keyName;
    }
}
