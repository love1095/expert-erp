package com.experterp.backend.service;

import com.experterp.backend.dto.InvoiceRequest;
import com.experterp.backend.dto.InvoiceResponse;
import com.experterp.backend.dto.PaymentRequest;
import com.experterp.backend.entity.*;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceSequenceRepository sequenceRepository;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;

    public Page<InvoiceResponse> findAll(int page, int size, Invoice.InvoiceType type) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        if (type != null) {
            return invoiceRepository.findByType(type, pageable).map(this::toResponse);
        }
        return invoiceRepository.findAll(pageable).map(this::toResponse);
    }

    public InvoiceResponse findById(Long id) {
        return toResponse(getInvoice(id));
    }

    @Transactional
    public InvoiceResponse create(InvoiceRequest req) {
        Invoice invoice = new Invoice();
        invoice.setType(req.getType());
        invoice.setPartyName(req.getPartyName());
        invoice.setSaleOrderId(req.getSaleOrderId());
        invoice.setPurchaseOrderId(req.getPurchaseOrderId());
        invoice.setIssueDate(req.getIssueDate());
        invoice.setDueDate(req.getDueDate());
        invoice.setCurrency(req.getCurrency() != null ? req.getCurrency() : "USD");
        invoice.setNotes(req.getNotes());
        invoice.setInvoiceNumber(nextInvoiceNumber());

        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceRequest.ItemRequest ir : req.getItems()) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoice(invoice);
            if (ir.getProductId() != null) {
                Product p = productRepository.findById(ir.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + ir.getProductId()));
                item.setProduct(p);
            }
            item.setDescription(ir.getDescription());
            item.setQuantity(ir.getQuantity());
            item.setUnitPrice(ir.getUnitPrice());
            BigDecimal lineTotal = ir.getQuantity().multiply(ir.getUnitPrice());
            item.setTotalPrice(lineTotal);
            total = total.add(lineTotal);
            invoice.getItems().add(item);
        }
        invoice.setTotalAmount(total);

        Invoice saved = invoiceRepository.save(invoice);
        auditService.log("INVOICE", saved.getId(), "CREATE",
                Map.of("invoiceNumber", saved.getInvoiceNumber(), "type", req.getType(), "total", total));
        return toResponse(saved);
    }

    @Transactional
    public InvoiceResponse issue(Long id) {
        Invoice inv = getInvoice(id);
        if (inv.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT invoices can be issued");
        }
        inv.setStatus(Invoice.InvoiceStatus.ISSUED);
        if (inv.getIssueDate() == null) inv.setIssueDate(LocalDate.now());
        Invoice saved = invoiceRepository.save(inv);
        auditService.log("INVOICE", id, "ISSUE", Map.of("invoiceNumber", inv.getInvoiceNumber()));
        return toResponse(saved);
    }

    @Transactional
    public InvoiceResponse cancel(Long id) {
        Invoice inv = getInvoice(id);
        if (inv.getStatus() == Invoice.InvoiceStatus.PAID) {
            throw new IllegalStateException("Cannot cancel a paid invoice");
        }
        inv.setStatus(Invoice.InvoiceStatus.CANCELLED);
        Invoice saved = invoiceRepository.save(inv);
        auditService.log("INVOICE", id, "CANCEL", Map.of("invoiceNumber", inv.getInvoiceNumber()));
        return toResponse(saved);
    }

    @Transactional
    public InvoiceResponse addPayment(Long id, PaymentRequest req) {
        Invoice inv = getInvoice(id);
        if (inv.getStatus() == Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Issue the invoice before recording a payment");
        }
        if (inv.getStatus() == Invoice.InvoiceStatus.CANCELLED) {
            throw new IllegalStateException("Cannot pay a cancelled invoice");
        }
        if (inv.getStatus() == Invoice.InvoiceStatus.PAID) {
            throw new IllegalStateException("Invoice is already fully paid");
        }

        Payment payment = new Payment();
        payment.setInvoice(inv);
        payment.setAmount(req.getAmount());
        payment.setMethod(req.getMethod());
        paymentRepository.save(payment);
        inv.getPayments().add(payment);

        BigDecimal totalPaid = inv.getPayments().stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPaid.compareTo(inv.getTotalAmount()) >= 0) {
            inv.setStatus(Invoice.InvoiceStatus.PAID);
            invoiceRepository.save(inv);
        }

        auditService.log("INVOICE", id, "PAYMENT",
                Map.of("amount", req.getAmount(), "method", req.getMethod(), "totalPaid", totalPaid));
        return toResponse(inv);
    }

    private String nextInvoiceNumber() {
        Long tenantId = TenantContext.get();
        InvoiceSequence seq = sequenceRepository.findByIdForUpdate(tenantId)
                .orElseGet(() -> {
                    InvoiceSequence s = new InvoiceSequence();
                    s.setTenantId(tenantId);
                    s.setCurrentNumber(0L);
                    return sequenceRepository.save(s);
                });
        seq.setCurrentNumber(seq.getCurrentNumber() + 1);
        sequenceRepository.save(seq);
        return String.format("INV-%04d", seq.getCurrentNumber());
    }

    private Invoice getInvoice(Long id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
    }

    private InvoiceResponse toResponse(Invoice inv) {
        InvoiceResponse r = new InvoiceResponse();
        r.setId(inv.getId());
        r.setInvoiceNumber(inv.getInvoiceNumber());
        r.setType(inv.getType());
        r.setPartyName(inv.getPartyName());
        r.setSaleOrderId(inv.getSaleOrderId());
        r.setPurchaseOrderId(inv.getPurchaseOrderId());
        r.setStatus(inv.getStatus());
        r.setIssueDate(inv.getIssueDate());
        r.setDueDate(inv.getDueDate());
        r.setTotalAmount(inv.getTotalAmount());
        r.setCurrency(inv.getCurrency());
        r.setNotes(inv.getNotes());
        r.setCreatedAt(inv.getCreatedAt());

        r.setItems(inv.getItems().stream().map(item -> {
            InvoiceResponse.ItemResponse ir = new InvoiceResponse.ItemResponse();
            ir.setId(item.getId());
            if (item.getProduct() != null) {
                ir.setProductId(item.getProduct().getId());
                ir.setProductName(item.getProduct().getName());
                ir.setProductSku(item.getProduct().getSku());
            }
            ir.setDescription(item.getDescription());
            ir.setQuantity(item.getQuantity());
            ir.setUnitPrice(item.getUnitPrice());
            ir.setTotalPrice(item.getTotalPrice());
            return ir;
        }).collect(Collectors.toList()));

        r.setPayments(inv.getPayments().stream().map(p -> {
            InvoiceResponse.PaymentResponse pr = new InvoiceResponse.PaymentResponse();
            pr.setId(p.getId());
            pr.setAmount(p.getAmount());
            pr.setMethod(p.getMethod());
            pr.setPaidAt(p.getPaidAt());
            return pr;
        }).collect(Collectors.toList()));

        BigDecimal paid = inv.getPayments().stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        r.setAmountPaid(paid);

        return r;
    }
}
