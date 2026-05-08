package com.experterp.backend.multitenancy;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.stereotype.Component;

@Component
public class TenantResolver implements CurrentTenantIdentifierResolver<Long> {

    @Override
    public Long resolveCurrentTenantIdentifier() {
        Long id = TenantContext.get();
        return id != null ? id : 0L; // 0L = no tenant (system ops)
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}
