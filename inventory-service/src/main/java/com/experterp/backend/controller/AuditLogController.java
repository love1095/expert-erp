package com.experterp.backend.controller;

import com.experterp.backend.entity.AuditLog;
import com.experterp.backend.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/audit-log")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogRepository auditLogRepository;

    @GetMapping
    public Page<AuditLog> list(
            @RequestParam(required = false) String entityType,
            // One record's history, e.g. a product's activity log
            @RequestParam(required = false) Long entityId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return auditLogRepository.search(entityType, entityId, action, username,
                PageRequest.of(page, size));
    }
}
