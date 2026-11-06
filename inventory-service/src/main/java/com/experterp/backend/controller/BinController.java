package com.experterp.backend.controller;

import com.experterp.backend.service.BinService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Phase N1: bins per warehouse, default bins, stock per bin, moves between bins, opening bins from Cin7. */
@RestController
@RequestMapping("/api/bins")
@RequiredArgsConstructor
public class BinController {

    private final BinService bins;

    @GetMapping
    public List<BinService.Bin> list(@RequestParam Long warehouseId) {
        return bins.bins(warehouseId);
    }

    @PostMapping
    public BinService.Bin create(@RequestBody BinService.BinRequest req) {
        return bins.saveBin(null, req);
    }

    @PutMapping("/{id}")
    public BinService.Bin update(@PathVariable Long id, @RequestBody BinService.BinRequest req) {
        return bins.saveBin(id, req);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        bins.deleteBin(id);
    }

    @GetMapping("/stock")
    public BinService.ProductBins stock(@RequestParam Long productId, @RequestParam Long warehouseId) {
        return bins.productBins(productId, warehouseId);
    }

    @PutMapping("/default")
    public void setDefault(@RequestBody Map<String, Long> body) {
        bins.setDefaultBin(body.get("productId"), body.get("warehouseId"), body.get("binId"));
    }

    public record MoveRequest(Long productId, Long warehouseId, Long fromBinId, Long toBinId, BigDecimal quantity) {}

    @PostMapping("/move")
    public BinService.ProductBins move(@RequestBody MoveRequest r) {
        return bins.move(r.productId(), r.warehouseId(), r.fromBinId(), r.toBinId(), r.quantity(), "Moved by hand");
    }

    @PostMapping(value = "/import", consumes = "multipart/form-data")
    public BinService.ImportResult importBins(@RequestParam("file") MultipartFile file, @RequestParam Long warehouseId,
                                              @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        try (var in = file.getInputStream()) {
            return bins.importBins(in, warehouseId, apply);
        }
    }
}
