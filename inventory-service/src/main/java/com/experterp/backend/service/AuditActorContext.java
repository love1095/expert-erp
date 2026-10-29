package com.experterp.backend.service;

import com.experterp.backend.entity.AuditLog.ActorType;

import java.util.function.Supplier;

/**
 * Names the non-user actor for audit entries written on the current thread
 * (scheduled jobs, webhooks). When unset, AuditService uses the authenticated
 * user, or SYSTEM if there is none.
 */
public class AuditActorContext {

    public record Actor(ActorType type, String id) {}

    private static final ThreadLocal<Actor> CURRENT = new ThreadLocal<>();

    public static Actor get() { return CURRENT.get(); }

    public static <T> T runAs(ActorType type, String id, Supplier<T> work) {
        Actor previous = CURRENT.get();
        CURRENT.set(new Actor(type, id));
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
