package com.experterp.backend.controller;

import com.experterp.backend.service.VatReturnService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.LocalDate;

/** The VAT return for a period in HMRC's nine boxes, with the rates and documents behind it (phase H3). */
@RestController
@RequestMapping("/api/reports/vat-return")
@RequiredArgsConstructor
public class VatReturnController {

    private final VatReturnService vatReturnService;

    @GetMapping
    public VatReturnService.VatReturn vatReturn(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return vatReturnService.vatReturn(from, to);
    }

    // The documents behind the return, to check it line by line
    @GetMapping(value = "/csv", produces = "text/csv")
    public void csv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, HttpServletResponse response) throws IOException {
        VatReturnService.VatReturn r = vatReturnService.vatReturn(from, to);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"VAT_" + from + "_" + to + ".csv\"");
        var out = response.getWriter();
        out.write('﻿');
        CSVPrinter p = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader("Side", "Type", "Number", "Date", "Party", "Net", "VAT").build());
        for (VatReturnService.Document d : r.documents()) {
            p.printRecord(d.side(), d.kind().equals("CREDIT_NOTE") ? "Credit note" : d.side().equals("SALES") ? "Invoice" : "Bill",
                    d.number(), d.date(), d.party(), d.net(), d.vat());
        }
        VatReturnService.Boxes b = r.boxes();
        p.println();
        String[][] boxes = {{"Box 1", b.box1().toPlainString()}, {"Box 2", b.box2().toPlainString()}, {"Box 3", b.box3().toPlainString()},
                {"Box 4", b.box4().toPlainString()}, {"Box 5", b.box5().toPlainString()}, {"Box 6", b.box6().toPlainString()},
                {"Box 7", b.box7().toPlainString()}, {"Box 8", b.box8().toPlainString()}, {"Box 9", b.box9().toPlainString()}};
        for (String[] box : boxes) p.printRecord(box[0], "", "", "", "", "", box[1]);
        p.flush();
    }
}
