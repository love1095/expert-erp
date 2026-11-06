package com.experterp.backend.controller;

import com.experterp.backend.service.CutoverCheckService;
import com.experterp.backend.service.TaskImportService;
import com.experterp.backend.service.TaskImportService.Kind;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Phase J: Cin7's sale / purchase task CSV import (preview, then import), its logs, and the go-live check. */
@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_DATA_IMPORT')")
public class DataImportController {

    private final TaskImportService importService;
    private final CutoverCheckService cutoverCheckService;

    /** Cin7's sale task import options: invoice status, pick-pack-ship mode, backordered on low stock, export to Xero. */
    @PostMapping(value = "/api/import/sales", consumes = "multipart/form-data")
    public TaskImportService.Result importSales(@RequestParam("file") MultipartFile file,
                                                @RequestParam(defaultValue = "AUTHORISED") TaskImportService.InvoiceStatus status,
                                                @RequestParam(defaultValue = "NO_PICKING") TaskImportService.Fulfilment fulfilment,
                                                @RequestParam(defaultValue = "false") boolean backorder,
                                                @RequestParam(defaultValue = "true") boolean exportToXero,
                                                @RequestParam(required = false) Long warehouseId,
                                                @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        return run(Kind.SALES, file, new TaskImportService.Options(status, fulfilment, backorder, false, false, exportToXero, warehouseId), apply);
    }

    /** Cin7's purchase task import options: invoice status, generate stock received from invoice lines, export to Xero. */
    @PostMapping(value = "/api/import/purchases", consumes = "multipart/form-data")
    public TaskImportService.Result importPurchases(@RequestParam("file") MultipartFile file,
                                                    @RequestParam(defaultValue = "AUTHORISED") TaskImportService.InvoiceStatus status,
                                                    @RequestParam(defaultValue = "false") boolean generateReceived,
                                                    @RequestParam(defaultValue = "false") boolean alreadyReceived,
                                                    @RequestParam(defaultValue = "true") boolean exportToXero,
                                                    @RequestParam(required = false) Long warehouseId,
                                                    @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        return run(Kind.PURCHASES, file, new TaskImportService.Options(status, null, false, generateReceived, alreadyReceived,
                exportToXero, warehouseId), apply);
    }

    @GetMapping("/api/import/logs")
    public List<TaskImportService.Log> logs() {
        return importService.logs();
    }

    /** Cin7's template headings (same columns, so a file made for Cin7 imports here). */
    @GetMapping("/api/import/{kind}/template")
    public ResponseEntity<byte[]> template(@PathVariable String kind) {
        boolean sales = kind.equalsIgnoreCase("sales");
        String csv = sales ? SALE_TEMPLATE : PURCHASE_TEMPLATE;
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + (sales ? "SaleTaskTemplate.csv" : "PurchaseTaskTemplate.csv") + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/api/cutover/check")
    public CutoverCheckService.Check check() {
        return cutoverCheckService.check();
    }

    private TaskImportService.Result run(Kind kind, MultipartFile file, TaskImportService.Options options, boolean apply) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        try (var in = file.getInputStream()) {
            return importService.importCsv(kind, in, file.getOriginalFilename(), options, apply);
        }
    }

    // Cin7 Core's templates (Sale / Purchase → Import CSV file → Download CSV template), plus our optional OrderNumber
    static final String SALE_TEMPLATE = "RecordType*,CustomerName*,InvoiceNumber*,OrderNumber,Reference/Comment/Note,Product*,Quantity*,"
            + "Price/Amount*,Discount,Tax,Total*,Account,TaxRule*,DropShip,CurrencyConversionRate,DatePaid*,CustomerContact,CustomerPhone,"
            + "CustomerEmail,SalesRepresentative*,ShipmentRequiredByDate,YourBaseCurrency*,CustomerCurrency*,Terms,PriceTier,StockLocation,"
            + "MemoOnInvoice,InvoiceDate*/ExpireDate,InvoiceDueDate,TaxInclusive*,ShippingAddressLine1*,ShippingAddressLine2,ShipToOther*,"
            + "ShippingCity*,ShippingProvince*,ShippingPostcode*,ShippingCountry*,ShipToCompany*,BillingAddressLine1*,BillingAddressLine2,"
            + "BillingCity*,BillingProvince*,BillingPostcode*,BillingCountry*,CreditNoteNumber,CreditNoteDate,CustomField1,CustomField2,"
            + "CustomField3,CustomField4,CustomField5,CustomField6,CustomField7,CustomField8,CustomField9,CustomField10,CarrierCode,"
            + "CarrierServiceCode,ShipToContact,ShippingNotes\r\n";
    static final String PURCHASE_TEMPLATE = "RecordType*,SupplierName*,InvoiceNumber*,OrderNumber,Reference/Serial/Note,Product*,Quantity*,"
            + "Price/Amount*,Discount,Tax,Total*,Account^,TaxRule*,CurrencyConversionRate,DatePaid/DateReceived*,InvoiceDate*/ExpireDate,"
            + "StockLocation^,SupplierContact,SupplierPhone,ShipmentRequiredByDate,YourBaseCurrency*,SupplierCurrency*,Terms,MemoOnInvoice,"
            + "InvoiceDueDate,TaxInclusive*,ShippingAddressLine1*,ShippingAddressLine2,ShipToOther*,ShippingCity*,ShippingProvince*,"
            + "ShippingPostcode*,ShippingCountry*,VendorAddressLine1*,VendorAddressLine2,VendorCity*,VendorProvince*,VendorPostcode*,"
            + "VendorCountry*,CreditNoteNumber^,CreditNoteDate^,CustomField1^,CustomField2^,CustomField3^,CustomField4^,CustomField5^,"
            + "CustomField6^,CustomField7^,CustomField8^,CustomField9^,CustomField10^\r\n";
}
