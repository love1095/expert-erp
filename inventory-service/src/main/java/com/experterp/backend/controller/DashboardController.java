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
    private final com.experterp.backend.service.DashboardLayoutService layoutService;

    // Manage dashboard (like Cin7): the signed-in user's layout, else the organisation's default, else the standard one
    @GetMapping("/layout")
    public com.experterp.backend.service.DashboardLayoutService.Result layout(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.experterp.backend.security.UserDetailsImpl me) {
        return layoutService.forUser(me.getId());
    }

    @org.springframework.web.bind.annotation.PutMapping("/layout")
    public com.experterp.backend.service.DashboardLayoutService.Result saveLayout(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.experterp.backend.security.UserDetailsImpl me,
            @org.springframework.web.bind.annotation.RequestBody com.experterp.backend.service.DashboardLayoutService.Layout layout) {
        return layoutService.save(me.getId(), layout);
    }

    // Reset layout: back to the organisation's default
    @org.springframework.web.bind.annotation.DeleteMapping("/layout")
    public com.experterp.backend.service.DashboardLayoutService.Result resetLayout(
            @org.springframework.security.core.annotation.AuthenticationPrincipal com.experterp.backend.security.UserDetailsImpl me) {
        return layoutService.reset(me.getId());
    }

    // An admin makes their layout the default for everyone who has not chosen their own (empty body: remove it)
    @org.springframework.web.bind.annotation.PutMapping("/layout/default")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
    public void saveDefault(@org.springframework.web.bind.annotation.RequestBody(required = false) com.experterp.backend.service.DashboardLayoutService.Layout layout) {
        layoutService.saveDefault(layout);
    }

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
