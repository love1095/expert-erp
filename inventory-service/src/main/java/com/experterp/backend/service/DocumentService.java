package com.experterp.backend.service;

import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PurchaseOrderResponse;
import com.experterp.backend.dto.SaleOrderResponse;
import com.experterp.backend.entity.*;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.SupplierRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.DocumentModel.Field;
import com.experterp.backend.service.DocumentModel.Line;
import com.experterp.backend.service.DocumentModel.Party;
import com.experterp.backend.service.DocumentModel.Total;
import com.experterp.backend.service.DocumentModel.VatRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * The organisation's documents as PDF (phase D5): quote, sale order, invoice, credit note and purchase order. Built
 * from the same responses the screens show, so the figures are the same; company details from the settings.
 */
@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final SaleOrderService saleOrderService;
    private final InvoiceService invoiceService;
    private final PurchaseOrderService purchaseOrderService;
    private final CompanyDetailsService companyDetailsService;
    private final CustomerRepository customerRepository;
    private final SupplierRepository supplierRepository;
    private final WarehouseRepository warehouseRepository;
    private final PdfRenderer renderer;
    private final DocumentTemplateService templateService;

    public record Pdf(String fileName, byte[] bytes) {}

    @Transactional(readOnly = true)
    public Pdf saleOrder(Long id) {
        return saleOrder(id, null);
    }

    /** {@code templateId}: a template of the document's type (I4), or null for the type's default. */
    @Transactional(readOnly = true)
    public Pdf saleOrder(Long id, Long templateId) {
        DocumentModel m = laidOut(saleOrderModel(saleOrderService.findById(id), company()), templateId);
        return new Pdf(m.fileName(), renderer.render(m));
    }

    @Transactional(readOnly = true)
    public Pdf invoice(Long id) {
        return invoice(id, null);
    }

    @Transactional(readOnly = true)
    public Pdf invoice(Long id, Long templateId) {
        DocumentModel m = laidOut(invoiceModel(invoiceService.findById(id), company()), templateId);
        return new Pdf(m.fileName(), renderer.render(m));
    }

    @Transactional(readOnly = true)
    public Pdf purchaseOrder(Long id) {
        return purchaseOrder(id, null);
    }

    @Transactional(readOnly = true)
    public Pdf purchaseOrder(Long id, Long templateId) {
        DocumentModel m = laidOut(purchaseOrderModel(purchaseOrderService.findById(id), company()), templateId);
        return new Pdf(m.fileName(), renderer.render(m));
    }

    // The document's type from its model (titles set below), then the template asked for or the type's default
    DocumentModel laidOut(DocumentModel m, Long templateId) {
        DocumentTemplateService.DocType type = switch (m.title()) {
            case "Quote" -> DocumentTemplateService.DocType.QUOTE;
            case "Sales order" -> DocumentTemplateService.DocType.SALES_ORDER;
            case "Credit note" -> DocumentTemplateService.DocType.CREDIT_NOTE;
            case "Purchase order" -> DocumentTemplateService.DocType.PURCHASE_ORDER;
            default -> DocumentTemplateService.DocType.INVOICE;
        };
        return DocumentTemplateService.apply(m, templateService.forPrinting(type, templateId).orElse(null));
    }

    /** Several documents of one kind in one PDF, in the order given (Cin7's Print on ticked rows). */
    @Transactional(readOnly = true)
    public Pdf many(String kind, List<Long> ids) {
        return many(kind, ids, null);
    }

    /** {@code templateId}: used for every document of its type; the others print with their type's default. */
    @Transactional(readOnly = true)
    public Pdf many(String kind, List<Long> ids, Long templateId) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Choose the documents to print");
        if (ids.size() > 100) throw new IllegalArgumentException("Print at most 100 documents at a time");
        Letterhead company = company();
        List<DocumentModel> models = new ArrayList<>();
        for (Long id : ids) {
            DocumentModel m = switch (kind) {
                case "sale-orders" -> saleOrderModel(saleOrderService.findById(id), company);
                case "invoices" -> invoiceModel(invoiceService.findById(id), company);
                case "purchase-orders" -> purchaseOrderModel(purchaseOrderService.findById(id), company);
                default -> throw new IllegalArgumentException("Unknown document kind: " + kind);
            };
            DocumentModel standard = laidOut(m, null);
            if (templateId != null) {
                try { standard = laidOut(m, templateId); } catch (IllegalArgumentException wrongType) { /* other type: its default */ }
            }
            models.add(standard);
        }
        String name = models.size() == 1 ? models.get(0).fileName() : kind + "-" + LocalDate.now() + ".pdf";
        return new Pdf(name, renderer.renderAll(models));
    }

    // ---- sale orders and quotes ----

    DocumentModel saleOrderModel(SaleOrderResponse so, Letterhead company) {
        boolean quote = so.getStatus() == SaleOrder.Status.QUOTE || so.getStatus() == SaleOrder.Status.REJECTED;
        String number = so.getOrderNumber() != null ? so.getOrderNumber() : "SO-" + so.getId();
        String stamp = switch (so.getStatus()) {
            case DRAFT -> "Draft";
            case CANCELLED -> "Cancelled";
            case REJECTED -> "Rejected";
            default -> quote && so.isQuoteExpired() ? "Expired" : null;
        };
        List<Field> fields = new ArrayList<>();
        if (quote) {
            field(fields, "Quote date", date(so.getCreatedAt()));
            field(fields, "Valid until", date(so.getQuoteValidUntil()));
        } else {
            field(fields, "Order date", date(so.getOrderedAt() != null ? so.getOrderedAt() : so.getCreatedAt()));
            field(fields, "Ship by", date(so.getShipBy()));
        }
        field(fields, "Your reference", so.getCustomerReference());
        field(fields, "Sales rep", so.getSalesRep());
        field(fields, "Terms", so.getPaymentTerm());
        if (!quote) field(fields, "Carrier", so.getCarrier());

        List<Line> lines = new ArrayList<>();
        List<Taxed> taxed = new ArrayList<>();
        for (SaleOrderResponse.Line l : so.getLines()) {
            BigDecimal qty = l.getQuantity().subtract(nz(l.getCancelledQuantity()));
            if (qty.signum() <= 0) continue;
            lines.add(new Line(l.getProductSku(), null, l.getProductName(), qty(qty), money(l.getUnitPrice()),
                    percent(l.getDiscountPercent()), rate(l.getTaxRate()), money(l.getNetAmount()), false));
            taxed.add(new Taxed(l.getTaxName(), l.getTaxRate(), l.getNetAmount(), l.getTaxAmount()));
        }
        for (SaleOrderResponse.Charge c : so.getCharges()) {
            lines.add(new Line(null, null, c.getDescription(), qty(c.getQuantity()), money(c.getUnitPrice()),
                    percent(c.getDiscountPercent()), rate(c.getTaxRate()), money(c.getNetAmount()), true));
            taxed.add(new Taxed(c.getTaxName(), c.getTaxRate(), c.getNetAmount(), c.getTaxAmount()));
        }
        List<Total> totals = List.of(
                new Total("Total before VAT", money(so.getSubtotal()), false),
                new Total("VAT", money(so.getTaxTotal()), false),
                new Total("Total", money(so.getTotal()), true));
        return new DocumentModel(quote ? "Quote" : "Sales order", number, stamp, company.forPrint(),
                orderParties(quote ? "Quote for" : "Customer", so),
                fields, true, false, anyDiscount(lines), lines, totals, vatSummary(taxed), so.getNotes(), null, number + ".pdf");
    }

    // ---- invoices and credit notes ----

    DocumentModel invoiceModel(InvoiceResponse inv, Letterhead company) {
        if (inv.getType() == Invoice.InvoiceType.PURCHASE) {
            throw new IllegalStateException(inv.getInvoiceNumber() + " is a supplier's bill: their own document, not printed here");
        }
        boolean credit = inv.getKind() == Invoice.Kind.CREDIT_NOTE;
        String stamp = switch (inv.getStatus()) {
            case DRAFT -> "Draft";
            case CANCELLED -> "Cancelled";
            case PAID -> credit ? null : "Paid";
            default -> null;
        };
        SaleOrderResponse so = inv.getSaleOrderId() == null ? null : saleOrderService.findById(inv.getSaleOrderId());
        List<Field> fields = new ArrayList<>();
        field(fields, credit ? "Date" : "Invoice date", date(inv.getIssueDate()));
        if (!credit) field(fields, "Due date", date(inv.getDueDate()));
        if (credit) field(fields, "Credits invoice", inv.getCreditedInvoiceNumber());
        if (so != null) {
            field(fields, "Order", so.getOrderNumber());
            field(fields, "Your reference", so.getCustomerReference());
            if (!credit) field(fields, "Terms", so.getPaymentTerm());
        }

        List<Line> lines = new ArrayList<>();
        List<Taxed> taxed = new ArrayList<>();
        for (InvoiceResponse.ItemResponse i : inv.getItems()) {
            boolean charge = i.getProductId() == null;
            String description = i.getProductName() != null ? i.getProductName() : i.getDescription();
            lines.add(new Line(i.getProductSku(), null, description, qty(i.getQuantity()), money(i.getUnitPrice()),
                    percent(i.getDiscountPercent()), rate(i.getTaxRate()), money(i.getTotalPrice()), charge));
            taxed.add(new Taxed(i.getTaxName(), i.getTaxRate(), i.getTotalPrice(), i.getTaxAmount()));
        }
        List<Total> totals = new ArrayList<>(List.of(
                new Total("Total before VAT", money(inv.getSubtotal()), false),
                new Total("VAT", money(inv.getTaxTotal()), false)));
        BigDecimal paid = nz(inv.getAmountPaid());
        if (credit) {
            totals.add(new Total("Total credit", money(inv.getTotalAmount()), true));
        } else if (paid.signum() > 0 && inv.getStatus() != Invoice.InvoiceStatus.CANCELLED) {
            totals.add(new Total("Total", money(inv.getTotalAmount()), false));
            totals.add(new Total("Paid", money(paid), false));
            totals.add(new Total("Balance due", money(inv.getTotalAmount().subtract(paid).max(BigDecimal.ZERO)), true));
        } else {
            totals.add(new Total("Total due", money(inv.getTotalAmount()), true));
        }

        // How to pay: on invoices still to pay
        String payment = null;
        boolean toPay = !credit && inv.getStatus() == Invoice.InvoiceStatus.ISSUED && inv.getTotalAmount().compareTo(paid) > 0;
        if (toPay) {
            StringBuilder sb = new StringBuilder("Please pay ");
            sb.append(inv.getDueDate() != null ? "by " + date(inv.getDueDate()) + ", " : "").append("quoting ").append(inv.getInvoiceNumber()).append('.');
            if (company.bankDetails() != null) sb.append('\n').append(company.bankDetails());
            payment = sb.toString();
        }
        Long customerId = inv.getCustomerId() != null ? inv.getCustomerId() : so != null ? so.getCustomerId() : null;
        return new DocumentModel(credit ? "Credit note" : "Invoice", inv.getInvoiceNumber(), stamp, company.forPrint(),
                invoiceParties(credit ? "Credit to" : "Invoice to", customerId, inv.getPartyName(), so),
                fields, lines.stream().anyMatch(l -> l.code() != null), false, anyDiscount(lines), lines, totals,
                vatSummary(taxed), inv.getNotes(), payment, inv.getInvoiceNumber() + ".pdf");
    }

    private List<Party> orderParties(String label, SaleOrderResponse so) {
        List<Party> parties = new ArrayList<>();
        parties.add(customerParty(label, so.getCustomerId(), so.getCustomerName(), so.getBillingAddress()));
        Party ship = deliverTo(so.getShipToCompany(), so.getShipToContact(), so.getShippingAddress(), so.getCustomerName());
        if (ship != null) parties.add(ship);
        return parties;
    }

    private List<Party> invoiceParties(String label, Long customerId, String name, SaleOrderResponse so) {
        if (so == null) return List.of(customerParty(label, customerId, name));
        List<Party> parties = new ArrayList<>();
        parties.add(customerParty(label, customerId, name, so.getBillingAddress()));
        Party ship = deliverTo(so.getShipToCompany(), so.getShipToContact(), so.getShippingAddress(), name);
        if (ship != null && !label.startsWith("Credit")) parties.add(ship);
        return parties;
    }

    // ---- purchase orders ----

    DocumentModel purchaseOrderModel(PurchaseOrderResponse po, Letterhead company) {
        String stamp = switch (po.getStatus()) {
            case DRAFT -> "Draft";
            case CANCELLED -> "Cancelled";
            default -> null;
        };
        List<Field> fields = new ArrayList<>();
        field(fields, "Order date", date(po.getOrderedAt() != null ? po.getOrderedAt() : po.getCreatedAt()));
        field(fields, "Required by", date(po.getRequiredBy()));
        field(fields, "Your reference", po.getSupplierReference());
        field(fields, "Terms", po.getPaymentTerm());

        List<Line> lines = new ArrayList<>();
        List<Taxed> taxed = new ArrayList<>();
        for (PurchaseOrderResponse.Line l : po.getLines()) {
            lines.add(new Line(l.getProductSku(), l.getSupplierSku(), l.getProductName(),
                    qty(l.getQuantity()) + (l.getUnit() != null && !l.getUnit().isBlank() ? " " + l.getUnit() : ""),
                    money(l.getUnitCost()), percent(l.getDiscountPercent()), rate(l.getTaxRate()),
                    money(l.getNetAmount()), false));
            taxed.add(new Taxed(l.getTaxName(), l.getTaxRate(), l.getNetAmount(), l.getTaxAmount()));
        }
        for (PurchaseOrderResponse.Cost c : po.getCosts()) {
            String description = c.getDescription() + (c.getReference() != null ? " (" + c.getReference() + ")" : "");
            lines.add(new Line(null, null, description, qty(c.getQuantity()), money(c.getUnitPrice()),
                    percent(c.getDiscountPercent()), rate(c.getTaxRate()), money(c.getNetAmount()), true));
            taxed.add(new Taxed(c.getTaxName(), c.getTaxRate(), c.getNetAmount(), c.getTaxAmount()));
        }
        List<Total> totals = List.of(
                new Total("Total before VAT", money(po.getSubtotal()), false),
                new Total("VAT", money(po.getTaxTotal()), false),
                new Total("Total", money(po.getTotal()), true));

        List<Party> parties = new ArrayList<>();
        Supplier s = po.getSupplierId() == null ? null : supplierRepository.findById(po.getSupplierId()).orElse(null);
        List<String> supplierLines = new ArrayList<>();
        if (s != null) {
            add(supplierLines, s.getContactName());
            add(supplierLines, s.getEmail());
            add(supplierLines, s.getPhone());
            if (s.getTaxNumber() != null && !s.getTaxNumber().isBlank()) supplierLines.add("VAT no. " + s.getTaxNumber());
        }
        parties.add(new Party("Supplier", po.getSupplierName(), supplierLines));
        // Deliver to: the warehouse, at the company's address
        Warehouse w = po.getWarehouseId() == null ? null : warehouseRepository.findById(po.getWarehouseId()).orElse(null);
        List<String> deliver = new ArrayList<>();
        add(deliver, company.name());
        deliver.addAll(company.address());
        if (w != null && w.getLocation() != null && company.address().isEmpty()) add(deliver, w.getLocation());
        parties.add(new Party("Deliver to", po.getWarehouseName(), deliver));

        return new DocumentModel("Purchase order", po.getOrderNumber(), stamp, company.forPrint(), parties, fields,
                true, lines.stream().anyMatch(l -> l.supplierCode() != null), anyDiscount(lines), lines, totals,
                vatSummary(taxed), po.getNotes(), null, po.getOrderNumber() + ".pdf");
    }

    // ---- shared ----

    /** The company block of every document, plus its bank details (only invoices print them). */
    record Letterhead(String name, List<String> address, String phone, String email, String website, String vatNumber,
                   String companyNumber, String logoDataUri, String footer, String bankDetails) {
        DocumentModel.Company forPrint() {
            return new DocumentModel.Company(name, address, phone, email, website, vatNumber, companyNumber, logoDataUri, footer);
        }
    }

    Letterhead company() {
        CompanyDetailsService.Printed p = companyDetailsService.printed();
        CompanyDetailsService.Details d = p.details();
        String logo = p.logo() == null ? null
                : "data:" + p.logoContentType() + ";base64," + Base64.getEncoder().encodeToString(p.logo());
        return new Letterhead(p.name(), lines(d.address()), d.phone(), d.email(), d.website(), d.vatNumber(), d.companyNumber(),
                logo, d.documentFooter(), d.bankDetails());
    }

    Party customerParty(String label, Long customerId, String fallbackName) {
        return customerParty(label, customerId, fallbackName, null);
    }

    /** The customer with an address: the one given (the order's), else their billing address (G4). */
    Party customerParty(String label, Long customerId, String fallbackName, Address address) {
        Customer c = customerId == null ? null : customerRepository.findById(customerId).orElse(null);
        Address a = Address.clean(address);
        if (c == null) return new Party(label, fallbackName, a == null ? List.of() : a.lines());
        List<String> out = new ArrayList<>(a != null ? a.lines() : c.billingAddress().map(Address::lines).orElse(List.of()));
        c.getContacts().stream().filter(CustomerContact::isDefaultContact).findFirst()
                .or(() -> c.getContacts().stream().findFirst())
                .ifPresent(ct -> {
                    if (ct.getName() != null && !ct.getName().equals(c.getName())) add(out, "Attn: " + ct.getName());
                    add(out, ct.getEmail());
                    add(out, ct.getPhone());
                });
        if (c.getTaxNumber() != null && !c.getTaxNumber().isBlank()) out.add("VAT no. " + c.getTaxNumber().trim());
        return new Party(label, c.getName(), out);
    }

    /** Where the goods go (G4): ship-to company and contact, then the shipping address; null when there is none. */
    private static Party deliverTo(String company, String contact, Address shipping, String customerName) {
        Address a = Address.clean(shipping);
        if (a == null && company == null && contact == null) return null;
        List<String> out = new ArrayList<>();
        if (contact != null && !contact.equals(company)) out.add("Attn: " + contact);
        if (a != null) out.addAll(a.lines());
        return new Party("Deliver to", company != null ? company : customerName, out);
    }

    private record Taxed(String name, BigDecimal rate, BigDecimal net, BigDecimal vat) {}

    // Net and VAT per rate, as UK VAT invoices show them
    private static List<VatRow> vatSummary(List<Taxed> taxed) {
        Map<String, BigDecimal[]> byRate = new LinkedHashMap<>();
        for (Taxed t : taxed) {
            BigDecimal[] sums = byRate.computeIfAbsent(taxLabel(t.name(), t.rate()), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sums[0] = sums[0].add(nz(t.net()));
            sums[1] = sums[1].add(nz(t.vat()));
        }
        return byRate.entrySet().stream().map(e -> new VatRow(e.getKey(), money(e.getValue()[0]), money(e.getValue()[1]))).toList();
    }

    // On the lines: the rate only (the VAT summary names the rules)
    private static String rate(BigDecimal rate) {
        return rate == null ? "0%" : rate.stripTrailingZeros().toPlainString() + "%";
    }

    private static String taxLabel(String name, BigDecimal rate) {
        String pct = rate == null ? "0%" : rate.stripTrailingZeros().toPlainString() + "%";
        if (name == null || name.isBlank()) return pct;
        return name.startsWith(pct) ? name : pct + " " + name;
    }

    private static boolean anyDiscount(List<Line> lines) {
        return lines.stream().anyMatch(l -> l.discount() != null);
    }

    static List<String> lines(String address) {
        if (address == null || address.isBlank()) return List.of();
        String[] parts = address.contains("\n") ? address.split("\\r?\\n") : address.split(",\\s*");
        return Arrays.stream(parts).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static void add(List<String> out, String s) {
        if (s != null && !s.isBlank()) out.add(s.trim());
    }

    private static void field(List<Field> fields, String label, String value) {
        if (value != null && !value.isBlank()) fields.add(new Field(label, value));
    }

    static String money(BigDecimal v) {
        if (v == null) return "";
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.UK);
        return f.format(v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String qty(BigDecimal v) {
        if (v == null) return "";
        return NumberFormat.getNumberInstance(Locale.UK).format(v.stripTrailingZeros());
    }

    private static String percent(BigDecimal v) {
        return v == null || v.signum() == 0 ? null : v.stripTrailingZeros().toPlainString() + "%";
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    static String date(LocalDate d) {
        return d == null ? null : d.format(DATE);
    }

    private static String date(LocalDateTime d) {
        return d == null ? null : d.format(DATE);
    }
}
