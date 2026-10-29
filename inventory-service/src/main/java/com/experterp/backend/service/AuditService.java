package com.experterp.backend.service;

import com.experterp.backend.entity.AuditLog;
import com.experterp.backend.repository.AuditLogRepository;
import com.experterp.backend.security.UserDetailsImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void log(String entityType, Long entityId, String action, Object changes) {
        AuditLog entry = new AuditLog();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAction(action);

        AuditActorContext.Actor actor = AuditActorContext.get();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (actor != null) {
            entry.setActorType(actor.type());
            entry.setActorId(actor.id());
            entry.setUsername(actor.id());
        } else if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl u) {
            entry.setUserId(u.getId());
            entry.setUsername(u.getUsername());
            entry.setActorId(u.getUsername());
        } else if (auth != null && !"anonymousUser".equals(auth.getPrincipal())) {
            entry.setUsername(auth.getName());
            entry.setActorId(auth.getName());
        } else {
            entry.setActorType(AuditLog.ActorType.SYSTEM);
            entry.setUsername("system");
            entry.setActorId("system");
        }

        try {
            entry.setChanges(changes != null ? objectMapper.writeValueAsString(changes) : null);
        } catch (JsonProcessingException e) {
            entry.setChanges(changes.toString());
        }

        auditLogRepository.save(entry);
    }

    /** Name of whoever is acting on this thread — for created_by columns. */
    public String currentActorName() {
        AuditActorContext.Actor actor = AuditActorContext.get();
        if (actor != null) return actor.id();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !"anonymousUser".equals(auth.getPrincipal())) return auth.getName();
        return "system";
    }
}
