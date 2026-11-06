package com.experterp.backend.controller;

import com.experterp.backend.service.CoreReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Cin7's everyday sale, purchase and inventory reports (phase I3). */
@RestController
@RequestMapping("/api/reports/core")
@RequiredArgsConstructor
public class CoreReportController {

    private final CoreReportService service;

    // The reports this user's role may run
    @GetMapping
    public List<CoreReportService.Info> catalogue() {
        return service.catalogue();
    }

    @GetMapping("/{key}")
    public CoreReportService.Report run(@PathVariable String key,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.run(key, from, to);
    }
}
