package com.experterp.backend.controller;

import com.experterp.backend.service.DocumentTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Document templates like Cin7's (phase I4): everyone may print with them; admins add and change them. */
@RestController
@RequestMapping("/api/settings/document-templates")
@RequiredArgsConstructor
public class DocumentTemplateController {

    private final DocumentTemplateService service;

    @GetMapping
    public List<DocumentTemplateService.Template> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public DocumentTemplateService.Template create(@RequestBody DocumentTemplateService.Template t) {
        return service.save(null, t);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public DocumentTemplateService.Template update(@PathVariable Long id, @RequestBody DocumentTemplateService.Template t) {
        return service.save(id, t);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
