package com.experterp.backend.controller;

import com.experterp.backend.service.MtdVatService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Phase K4: VAT returns filed and Making Tax Digital (connect to HMRC, VAT periods, review, submit). */
@RestController
@RequestMapping("/api/vat")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_ACCOUNTING')")
public class MtdVatController {

    private final MtdVatService mtd;

    @GetMapping("/mtd")
    public MtdVatService.Status status() {
        return mtd.status();
    }

    @PutMapping("/mtd/vrn")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public MtdVatService.Status vrn(@RequestBody Map<String, String> body) {
        return mtd.setVrn(body.get("vrn"));
    }

    @PostMapping("/mtd/connect")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public Map<String, String> connect() {
        return Map.of("url", mtd.connect());
    }

    @PostMapping("/mtd/callback")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public MtdVatService.Status callback(@RequestBody Map<String, String> body) {
        return mtd.callback(body.get("code"), body.get("state"));
    }

    @DeleteMapping("/mtd")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public MtdVatService.Status disconnect() {
        return mtd.disconnect();
    }

    public record ObligationsRequest(LocalDate from, LocalDate to, MtdVatService.ClientInfo client) {}

    @PostMapping("/mtd/obligations")
    public List<MtdVatService.Period> obligations(@RequestBody ObligationsRequest req, HttpServletRequest http) {
        return mtd.obligations(req.from(), req.to(), withAddress(req.client(), http));
    }

    @GetMapping("/review")
    public MtdVatService.Review review(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return mtd.review(from, to);
    }

    public record SubmitRequest(String periodKey, LocalDate from, LocalDate to, LocalDate due, boolean declaration, MtdVatService.ClientInfo client) {}

    @PostMapping("/mtd/submit")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public MtdVatService.Filed submit(@RequestBody SubmitRequest req, HttpServletRequest http) {
        return mtd.submit(req.periodKey(), req.from(), req.to(), req.due(), req.declaration(), withAddress(req.client(), http));
    }

    public record ManualRequest(LocalDate from, LocalDate to, String note) {}

    @PostMapping("/returns/manual")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_SUPER_ADMIN', 'PERM_ADVISER')")
    public MtdVatService.Filed recordManual(@RequestBody ManualRequest req) {
        return mtd.recordManual(req.from(), req.to(), req.note());
    }

    @GetMapping("/returns")
    public List<MtdVatService.Filed> filed() {
        return mtd.filed();
    }

    // The user's public address as our server sees it (first X-Forwarded-For behind a proxy): HMRC's Gov-Client-Public-IP / -Port
    private static MtdVatService.ClientInfo withAddress(MtdVatService.ClientInfo c, HttpServletRequest http) {
        String fwd = http.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : http.getRemoteAddr();
        String port = http.getHeader("X-Forwarded-Port");
        Integer p = port != null && port.matches("\\d+") ? Integer.valueOf(port) : http.getRemotePort();
        MtdVatService.ClientInfo x = c == null ? new MtdVatService.ClientInfo(null, null, null, null, null, null, null, null, null, null, null, null) : c;
        return new MtdVatService.ClientInfo(x.userAgent(), x.deviceId(), x.doNotTrack(), x.screenWidth(), x.screenHeight(), x.scalingFactor(),
                x.colourDepth(), x.windowWidth(), x.windowHeight(), x.timezone(), ip, p);
    }
}
