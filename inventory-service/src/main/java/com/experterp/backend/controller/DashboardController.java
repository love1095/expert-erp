package com.experterp.backend.controller;

import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    // The period is from–to (both included); without them, the last `days` days, today included
    @GetMapping
    public DashboardResponse get(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "30") int days) {
        if (from != null || to != null) {
            if (from == null || to == null) throw new IllegalArgumentException("Give both from and to");
            return dashboardService.get(from, to);
        }
        if (days < 1 || days > DashboardService.MAX_DAYS)
            throw new IllegalArgumentException("days must be between 1 and " + DashboardService.MAX_DAYS);
        return dashboardService.get(days);
    }

    @GetMapping("/reorder")
    public Page<DashboardResponse.ReorderLine> reorder(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page must be ≥ 0 and size 1–100");
        return dashboardService.reorder(search, page, size);
    }
}
