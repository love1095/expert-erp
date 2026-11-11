package com.experterp.backend.service;

import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.PurchaseOrder;
import com.experterp.backend.entity.SaleOrder;
import com.experterp.backend.entity.Supplier;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.security.Permission;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * "Export to CSV format" on the sale and purchase lists: every order of the list's current search and filter (not
 * only the page shown), made on the server a page at a time. And "Export accounts payable": what is owed to each
 * supplier, one row per open bill, credit note or deposit. Money columns follow the permission to see them.
 */
@Service
@RequiredArgsConstructor
public class OrderListExportService {

    static final String[] SALES = {"Order #", "Status", "Fulfilment", "Customer", "Customer reference", "Documents", "Sales rep",
            "Warehouse", "Ship by", "Subtotal", "VAT", "Total inc VAT", "Invoiced", "Created"};
    static final String[] PURCHASES = {"PO #", "Status", "Supplier", "Supplier reference", "Bills", "Warehouse", "Required by",
            "Received", "Billed", "Paid", "Subtotal", "VAT", "Total inc VAT", "Created"};
    static final String[] PAYABLE = {"Supplier", "Type", "Number", "Date", "Due date", "Days overdue", "Total", "Outstanding"};

    private static final int PAGE = 500;

    private final SaleOrderService saleOrderService;
    private final PurchaseOrderService purchaseOrderService;
    private final PurchaseLedgerService purchaseLedger;
    private final SupplierRepository supplierRepository;
    private final AuditService auditService;

    public void sales(Long customerId, SaleOrder.Status status, String q, Writer out) throws IOException {
        CSVPrinter p = printer(out, SALES);
        int page = 0;
        Page<SaleOrderResponse> slice;
        do {
            slice = saleOrderService.findAll(customerId, status, q, page++, PAGE);
            for (SaleOrderResponse o : slice) {
                p.printRecord(o.getOrderNumber(), o.getStatus(), o.getFulfilmentStatus(), o.getCustomerName(), o.getCustomerReference(),
                        o.getInvoices() == null ? "" : String.join(" ", o.getInvoices().stream().map(SaleOrderResponse.InvoiceEntry::getInvoiceNumber).toList()),
                        o.getSalesRep(), o.getWarehouseName(), o.getShipBy(), money(o.getSubtotal()), money(o.getTaxTotal()), money(o.getTotal()),
                        money(o.getInvoicedTotal()), day(o.getCreatedAt()));
            }
            p.flush();
        } while (slice.hasNext());
        auditService.log("SALE_ORDER", null, "EXPORT", Map.of("file", "Sales", "rows", slice.getTotalElements()));
    }

    public void purchases(PurchaseOrder.Status status, String q, Writer out) throws IOException {
        boolean prices = Permission.current(Permission.PURCHASE_PRICES);
        CSVPrinter p = printer(out, PURCHASES);
        int page = 0;
        Page<PurchaseOrderResponse> slice;
        do {
            slice = purchaseOrderService.findAll(page++, PAGE, status, q);
            for (PurchaseOrderResponse o : slice) {
                p.printRecord(o.getOrderNumber(), o.getStatus(), o.getSupplierName(), o.getSupplierReference(),
                        o.getBills() == null ? "" : String.join(" ", o.getBills().stream().map(PurchaseOrderResponse.Bill::getInvoiceNumber).toList()),
                        o.getWarehouseName(), o.getRequiredBy(), o.getReceivingStatus(), o.getBillingStatus(), o.getPaymentStatus(),
                        prices ? money(o.getSubtotal()) : "", prices ? money(o.getTaxTotal()) : "", prices ? money(o.getTotal()) : "",
                        day(o.getCreatedAt()));
            }
            p.flush();
        } while (slice.hasNext());
        auditService.log("PURCHASE_ORDER", null, "EXPORT", Map.of("file", "Purchases", "rows", slice.getTotalElements()));
    }

    /** Open items with every supplier today, suppliers by name, oldest item first. */
    public void accountsPayable(Writer out) throws IOException {
        CSVPrinter p = printer(out, PAYABLE);
        LocalDate today = LocalDate.now();
        int rows = 0;
        for (Supplier s : supplierRepository.findAll(Sort.by("name"))) {
            for (PurchaseLedgerService.OpenItem i : purchaseLedger.openItems(s.getId(), today)) {
                p.printRecord(s.getName(), kind(i.kind()), i.number(), i.date(), i.dueDate(), Math.max(0, i.daysOverdue()),
                        money(i.total()), money(i.open()));
                rows++;
            }
        }
        p.flush();
        auditService.log("PURCHASE_ORDER", null, "EXPORT", Map.of("file", "AccountsPayable", "rows", rows));
    }

    private static CSVPrinter printer(Writer out, String[] header) throws IOException {
        Writer buffered = new BufferedWriter(out);
        // BOM so Excel opens it as UTF-8
        buffered.write('﻿');
        return new CSVPrinter(buffered, CSVFormat.DEFAULT.builder().setHeader(header).build());
    }

    private static String kind(String k) {
        if (k == null) return "";
        return switch (k) {
            case "INVOICE", "BILL" -> "Bill";
            case "CREDIT_NOTE" -> "Credit note";
            case "DEPOSIT" -> "Deposit";
            default -> k;
        };
    }

    private static String money(BigDecimal v) {
        return v == null ? "" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String day(LocalDateTime t) {
        return t == null ? "" : t.toLocalDate().toString();
    }
}
