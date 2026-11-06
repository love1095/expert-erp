package com.experterp.backend.controller;

import com.experterp.backend.service.InventoryMovementService;
import com.experterp.backend.service.SalesProfitService;
import com.experterp.backend.service.SalesProfitService.CogsMode;
import com.experterp.backend.service.SalesProfitService.GroupBy;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Sales profit and inventory movement reports (phase H4), like Cin7 Core's. They show cost and profit: managers and
 * admins only (Cin7 gives each user report permissions; phase I).
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN', 'SUPER_ADMIN')")
public class ProfitReportController {

    private final SalesProfitService salesProfitService;
    private final InventoryMovementService inventoryMovementService;

    @GetMapping("/sales-profit")
    public SalesProfitService.Report salesProfit(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(defaultValue = "WITHIN_DATES") CogsMode cogs,
                                                 @RequestParam(defaultValue = "CUSTOMER") GroupBy groupBy) {
        return salesProfitService.report(from, to, cogs, groupBy);
    }

    @GetMapping(value = "/sales-profit/csv", produces = "text/csv")
    public void salesProfitCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(defaultValue = "WITHIN_DATES") CogsMode cogs,
                               @RequestParam(defaultValue = "CUSTOMER") GroupBy groupBy,
                               HttpServletResponse response) throws IOException {
        SalesProfitService.Report r = salesProfitService.report(from, to, cogs, groupBy);
        boolean customer = groupBy != GroupBy.REP && groupBy != GroupBy.PRODUCT;
        boolean rep = groupBy != GroupBy.PRODUCT;
        boolean order = groupBy == GroupBy.ORDER || groupBy == GroupBy.REP_ORDER;
        boolean product = groupBy == GroupBy.PRODUCT;
        List<String> header = new ArrayList<>();
        if (rep && groupBy.name().startsWith("REP")) header.add("Sales representative");
        if (customer) header.add("Customer");
        if (rep && !groupBy.name().startsWith("REP")) header.add("Sales representative");
        if (order) header.addAll(List.of("Order #", "Invoice #", "Credit note #"));
        if (product) header.addAll(List.of("SKU", "Product", "Category", "Quantity"));
        header.addAll(List.of("Invoice", "Sale", "COGS", "Profit", "Gross margin %", "Markup %"));

        CSVPrinter p = start(response, "SalesProfit_" + groupBy.name().toLowerCase() + "_" + from + "_" + to + ".csv", header);
        for (SalesProfitService.Row row : r.rows()) {
            List<Object> v = new ArrayList<>();
            if (rep && groupBy.name().startsWith("REP")) v.add(row.salesRep());
            if (customer) v.add(row.customer());
            if (rep && !groupBy.name().startsWith("REP")) v.add(row.salesRep());
            if (order) v.addAll(List.of(nn(row.orderNumber()), row.invoices(), row.creditNotes()));
            if (product) v.addAll(List.of(nn(row.sku()), nn(row.productName()), nn(row.category()), row.quantity()));
            v.addAll(List.of(row.invoiced(), row.sale(), row.cogs(), row.profit(), nn(row.grossMargin()), nn(row.markup())));
            p.printRecord(v);
        }
        p.flush();
    }

    @GetMapping("/inventory-movement")
    public InventoryMovementService.Report inventoryMovement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                            @RequestParam(required = false) Long warehouseId,
                                                            @RequestParam(required = false) Long categoryId) {
        return inventoryMovementService.report(from, to, warehouseId, categoryId);
    }

    @GetMapping(value = "/inventory-movement/csv", produces = "text/csv")
    public void inventoryMovementCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Long warehouseId,
                                     @RequestParam(required = false) Long categoryId,
                                     HttpServletResponse response) throws IOException {
        InventoryMovementService.Report r = inventoryMovementService.report(from, to, warehouseId, categoryId);
        CSVPrinter p = start(response, "InventoryMovement_" + from + "_" + to + ".csv", List.of("Location", "SKU", "Product", "Unit",
                "Category", "Opening quantity", "Opening value", "In", "In value", "Out", "Out value", "Quantity on hand",
                "Unit cost", "Stock on hand"));
        for (InventoryMovementService.Row row : r.rows()) {
            p.printRecord(row.warehouse(), row.sku(), row.product(), nn(row.unit()), nn(row.category()),
                    row.openingQuantity(), row.openingValue(), row.inQuantity(), row.inValue(), row.outQuantity(), row.outValue(),
                    row.closingQuantity(), nn(row.unitCost()), row.closingValue());
        }
        p.flush();
    }

    private static CSVPrinter start(HttpServletResponse response, String filename, List<String> header) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        var out = response.getWriter();
        out.write('﻿');
        return new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader(header.toArray(String[]::new)).build());
    }

    private static Object nn(Object v) {
        return v == null ? "" : v instanceof BigDecimal b ? b.toPlainString() : v;
    }
}
