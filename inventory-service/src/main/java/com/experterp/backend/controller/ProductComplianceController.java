package com.experterp.backend.controller;

import com.experterp.backend.service.ProductComplianceService;
import com.experterp.backend.service.ProductFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Phase P1 / P4: a product's safety compliance, its files (images, datasheets, declarations, certificate), expiring certificates. */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductComplianceController {

    private final ProductComplianceService compliance;
    private final ProductFileService files;

    @GetMapping("/{id}/compliance")
    public ProductComplianceService.Compliance compliance(@PathVariable Long id) {
        return compliance.get(id);
    }

    @PutMapping("/{id}/compliance")
    public ProductComplianceService.Compliance save(@PathVariable Long id, @RequestBody ProductComplianceService.Request r) {
        return compliance.save(id, r);
    }

    @GetMapping("/certificates/expiring")
    public List<ProductComplianceService.Expiring> expiring(@RequestParam(defaultValue = "60") int days) {
        return compliance.expiring(days);
    }

    @GetMapping("/{id}/files")
    public List<ProductFileService.FileInfo> files(@PathVariable Long id) {
        return files.files(id);
    }

    @PostMapping(value = "/{id}/files", consumes = "multipart/form-data")
    public ProductFileService.FileInfo upload(@PathVariable Long id, @RequestParam String kind, @RequestParam("file") MultipartFile file) throws IOException {
        ProductFileService.Kind k;
        try {
            k = ProductFileService.Kind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown kind of file: " + kind);
        }
        return files.upload(id, k, file.getOriginalFilename(), file.getContentType(), file.getBytes());
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<byte[]> content(@PathVariable Long fileId) {
        ProductFileService.Content c = files.content(fileId);
        return ResponseEntity.ok()
                .header("Content-Disposition", ContentDisposition.inline().filename(c.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(c.contentType())).body(c.bytes());
    }

    @DeleteMapping("/files/{fileId}")
    public void delete(@PathVariable Long fileId) {
        files.delete(fileId);
    }

    @PostMapping("/files/{fileId}/main")
    public List<ProductFileService.FileInfo> makeMain(@PathVariable Long fileId) {
        return files.makeMain(fileId);
    }
}
