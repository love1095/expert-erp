package com.experterp.backend.controller;

import com.experterp.backend.service.AccessRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Users &amp; Roles like Cin7 Core (phase I1): admins only. */
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
public class AccessRoleController {

    private final AccessRoleService service;

    @GetMapping("/permissions")
    public List<AccessRoleService.PermissionInfo> permissions() {
        return service.catalogue();
    }

    @GetMapping("/roles")
    public List<AccessRoleService.Role> roles() {
        return service.roles();
    }

    @PostMapping("/roles")
    public AccessRoleService.Role create(@RequestBody AccessRoleService.RoleRequest req) {
        return service.create(req);
    }

    @PutMapping("/roles/{id}")
    public AccessRoleService.Role update(@PathVariable Long id, @RequestBody AccessRoleService.RoleRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/roles/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // Q5: several roles; their permissions add up
    @PutMapping("/users/{userId}/access-roles")
    public ResponseEntity<Void> assignAll(@PathVariable Long userId, @RequestBody Map<String, java.util.List<Long>> body) {
        service.assignAll(userId, body.get("accessRoleIds"));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/access-role")
    public ResponseEntity<Void> assign(@PathVariable Long userId, @RequestBody Map<String, Long> body) {
        service.assign(userId, body.get("accessRoleId"));
        return ResponseEntity.noContent().build();
    }
}
