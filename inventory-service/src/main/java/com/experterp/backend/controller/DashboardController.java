package com.experterp.backend.controller;

import com.experterp.backend.dto.DashboardResponse;
import com.experterp.backend.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    // days: length of the period for the charts and period figures, today included
    @GetMapping
    public DashboardResponse get(@RequestParam(defaultValue = "30") int days) {
        if (days < 1 || days > 365) throw new IllegalArgumentException("days must be between 1 and 365");
        return dashboardService.get(days);
    }
}
