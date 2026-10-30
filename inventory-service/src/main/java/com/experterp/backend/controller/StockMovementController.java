package com.experterp.backend.controller;

import com.experterp.backend.service.StockImportService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

import com.experterp.backend.dto.StockLevelResponse;
import com.experterp.backend.dto.StockMovementRequest;
import com.experterp.backend.dto.StockMovementResponse;
import com.experterp.backend.dto.StockTransferRequest;
import com.experterp.backend.service.StockMovementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StockMovementController {

    private final StockMovementService stockMovementService;
    private final StockImportService stockImportService;

    @PostMapping("/api/stock-movements")
    public ResponseEntity<StockMovementResponse> create(@Valid @RequestBody StockMovementRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockMovementService.create(req));
    }

    @GetMapping("/api/stock-movements")
    public Page<StockMovementResponse> findAll(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return stockMovementService.findAll(productId, warehouseId, categoryId, page, size);
    }

    @PostMapping("/api/stock-movements/transfer")
    public ResponseEntity<List<StockMovementResponse>> transfer(@Valid @RequestBody StockTransferRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(stockMovementService.transfer(req));
    }

    @GetMapping("/api/stock")
    public StockLevelResponse getStock(
            @RequestParam Long productId,
            @RequestParam Long warehouseId) {
        return stockMovementService.getStockLevel(productId, warehouseId);
    }

    @GetMapping("/api/stock/overview")
    public Page<StockLevelResponse> getOverview(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long categoryId) {
        return stockMovementService.getStockOverviewPage(page, size, categoryId);
    }

    // Cin7 stock file (Inventory → Availability → Export): stock in one warehouse set to the file's OnHand.
    // apply=false previews the changes without writing anything.
    @PostMapping(value = "/api/stock/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'SUPER_ADMIN')")
    public StockImportService.ImportResult importStock(@RequestParam("file") MultipartFile file,
                                                       @RequestParam Long warehouseId,
                                                       @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        try (var in = file.getInputStream()) {
            return stockImportService.importCsv(in, file.getOriginalFilename(), warehouseId, apply);
        }
    }

    @GetMapping(value = "/api/stock-movements/export/csv")
    public ResponseEntity<String> exportCsv(
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Long warehouseId) {
        String csv = stockMovementService.exportCsv(productId, warehouseId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"movements.csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }
}
