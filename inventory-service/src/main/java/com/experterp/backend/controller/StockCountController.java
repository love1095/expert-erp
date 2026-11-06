package com.experterp.backend.controller;

import com.experterp.backend.service.StockAdjustmentService;
import com.experterp.backend.service.StockTakeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Phase N5: stock adjustments with reasons and approval, and stock takes (freeze, count, variance, post). */
@RestController
@RequiredArgsConstructor
public class StockCountController {

    private final StockAdjustmentService adjustments;
    private final StockTakeService stockTakes;

    // ---- adjustments ----

    @GetMapping("/api/stock-adjustments/settings")
    public Map<String, Object> settings() {
        Map<String, Object> m = new HashMap<>();
        m.put("approvalValue", adjustments.approvalValue());
        m.put("reasons", StockAdjustmentService.REASONS);
        return m;
    }

    public record Settings(BigDecimal approvalValue) {}

    @PutMapping("/api/stock-adjustments/settings")
    public Map<String, Object> saveSettings(@RequestBody Settings s) {
        adjustments.setApprovalValue(s.approvalValue());
        return settings();
    }

    @GetMapping("/api/stock-adjustments")
    public List<StockAdjustmentService.Request> requests(@RequestParam(required = false) String status) {
        return adjustments.requests(status);
    }

    @PostMapping("/api/stock-adjustments")
    public StockAdjustmentService.Request adjust(@RequestBody StockAdjustmentService.AdjustRequest r) {
        return adjustments.adjust(r);
    }

    public record Decision(String note) {}

    @PostMapping("/api/stock-adjustments/{id}/approve")
    public StockAdjustmentService.Request approve(@PathVariable Long id, @RequestBody(required = false) Decision d) {
        return adjustments.approve(id, d == null ? null : d.note());
    }

    @PostMapping("/api/stock-adjustments/{id}/reject")
    public StockAdjustmentService.Request reject(@PathVariable Long id, @RequestBody(required = false) Decision d) {
        return adjustments.reject(id, d == null ? null : d.note());
    }

    // ---- stock takes ----

    @GetMapping("/api/stock-takes/lookup")
    public StockTakeService.Found lookup(@RequestParam String code) {
        return stockTakes.lookup(code);
    }

    @GetMapping("/api/stock-takes")
    public List<StockTakeService.StockTake> stockTakes(@RequestParam(required = false) String status) {
        return stockTakes.stockTakes(status);
    }

    @PostMapping("/api/stock-takes")
    public StockTakeService.StockTake start(@RequestBody StockTakeService.StartRequest r) {
        return stockTakes.start(r);
    }

    @GetMapping("/api/stock-takes/{id}")
    public StockTakeService.StockTake stockTake(@PathVariable Long id) {
        return stockTakes.stockTake(id);
    }

    public record Count(BigDecimal counted) {}

    @PutMapping("/api/stock-takes/{id}/lines/{lineId}")
    public StockTakeService.StockTake count(@PathVariable Long id, @PathVariable Long lineId, @RequestBody Count c) {
        return stockTakes.count(id, lineId, c.counted());
    }

    public record Scan(String code, Long binId, BigDecimal quantity) {}

    @PostMapping("/api/stock-takes/{id}/scan")
    public StockTakeService.StockTake scan(@PathVariable Long id, @RequestBody Scan s) {
        return stockTakes.scan(id, s.code(), s.binId(), s.quantity());
    }

    @PostMapping("/api/stock-takes/{id}/post")
    public StockTakeService.PostResult post(@PathVariable Long id) {
        return stockTakes.post(id);
    }

    @PostMapping("/api/stock-takes/{id}/cancel")
    public StockTakeService.StockTake cancel(@PathVariable Long id) {
        return stockTakes.cancel(id);
    }
}
