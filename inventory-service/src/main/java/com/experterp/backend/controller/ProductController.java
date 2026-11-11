package com.experterp.backend.controller;

import com.experterp.backend.dto.ProductRequest;
import com.experterp.backend.dto.ProductResponse;
import com.experterp.backend.entity.Product;
import com.experterp.backend.service.ProductDataFileService;
import com.experterp.backend.service.ProductImportService;
import com.experterp.backend.service.ProductService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ProductController {

    private final ProductService productService;
    private final ProductImportService importService;
    private final ProductDataFileService fileService;
    private final com.experterp.backend.service.ProductPickerService pickerService;

    // status: ACTIVE / DEPRECATED, omitted = all; q: part of the SKU or name
    @GetMapping
    public Page<ProductResponse> findAll(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Product.Status status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return productService.findAll(categoryId, status, q, page, Math.min(Math.max(size, 1), 200));
    }

    // Every product, minimal fields, for dropdowns; active ones unless a status is given ("ALL" = both)
    // Review R9: the selection list on order lines; supplierId puts that supplier's products first
    @GetMapping("/picker")
    public com.experterp.backend.service.ProductPickerService.Result picker(@RequestParam(required = false) String q,
            @RequestParam(required = false) Long supplierId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return pickerService.page(q, supplierId, page, size);
    }

    @GetMapping("/options")
    public List<ProductService.ProductOption> options(@RequestParam(defaultValue = "ACTIVE") String status) {
        return productService.options("ALL".equalsIgnoreCase(status) ? null : Product.Status.valueOf(status.toUpperCase(Locale.ROOT)));
    }

    // The products' files on the Export menu, in Cin7's columns and file names (type: ProductDataFileService.Kind);
    // without a type, the inventory list of one status (or all)
    @GetMapping(value = "/export", produces = "text/csv")
    public void export(@RequestParam(required = false) Product.Status status,
                       @RequestParam(required = false) ProductDataFileService.Kind type,
                       HttpServletResponse response) throws IOException {
        ProductDataFileService.Kind kind = type == null ? ProductDataFileService.Kind.INVENTORY_LIST : type;
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileService.fileName(kind) + "\"");
        if (type == null && status != null) importService.exportCsv(status, response.getWriter());
        else fileService.export(kind, response.getWriter());
    }

    // The products' files on the Import menu; a file of another type than the one chosen is refused.
    // Inventory list: creates or updates products by SKU. Stock on hand: apply=false previews the stock changes.
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_DATA_IMPORT')")
    public Object importCsv(@RequestParam("file") MultipartFile file,
                            @RequestParam(required = false) ProductDataFileService.Kind type,
                            @RequestParam(defaultValue = "false") boolean deprecateMissing,
                            @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        return fileService.importFile(type == null ? ProductDataFileService.Kind.INVENTORY_LIST : type, file.getBytes(),
                file.getOriginalFilename(), deprecateMissing, apply);
    }

    @GetMapping("/{id}")
    public ProductResponse findById(@PathVariable Long id) {
        return productService.findById(id);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(productService.create(req));
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest req) {
        return productService.update(id, req);
    }

    // Stock per warehouse and on open purchase orders
    @GetMapping("/{id}/stock")
    public com.experterp.backend.dto.ProductStockResponse stock(@PathVariable Long id) {
        return productService.stock(id);
    }

    // Cin7's More actions → Delete deprecated products (those without history)
    @PostMapping("/delete-deprecated")
    @PreAuthorize("hasAuthority('PERM_DATA_IMPORT')")
    public ProductService.DeleteDeprecatedResult deleteDeprecated() {
        return productService.deleteDeprecated();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
