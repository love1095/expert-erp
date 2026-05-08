package com.experterp.backend.multitenancy;

public class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    public static Long get() { return CURRENT.get(); }
    public static void set(Long tenantId) { CURRENT.set(tenantId); }
    public static void clear() { CURRENT.remove(); }
}
