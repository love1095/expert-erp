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

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetailsImpl u) {
            entry.setUserId(u.getId());
            entry.setUsername(u.getUsername());
        } else if (auth != null && !"anonymousUser".equals(auth.getPrincipal())) {
            entry.setUsername(auth.getName());
        } else {
            entry.setUsername("system");
        }

        try {
            entry.setChanges(changes != null ? objectMapper.writeValueAsString(changes) : null);
        } catch (JsonProcessingException e) {
            entry.setChanges(changes.toString());
        }

        auditLogRepository.save(entry);
    }
}
