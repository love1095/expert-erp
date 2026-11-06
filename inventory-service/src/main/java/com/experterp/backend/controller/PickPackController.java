package com.experterp.backend.controller;

import com.experterp.backend.service.DocumentService;
import com.experterp.backend.service.MailService;
import com.experterp.backend.service.PickPackService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Phase N2 / N3: pick lists, packing cartons, shipping cartons, the pick list PDF and the despatch note. */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class PickPackController {

    private final PickPackService pickPack;
    private final MailService mail;

    @GetMapping("/pick-lists")
    public List<PickPackService.PickList> pickLists(@RequestParam(required = false) String status) {
        return pickPack.pickLists(status);
    }

    @PostMapping("/pick-lists")
    public PickPackService.PickList create(@RequestBody Map<String, List<Long>> body) {
        return pickPack.create(body.get("orderIds"));
    }

    @GetMapping("/pick-lists/{id}")
    public PickPackService.PickList pickList(@PathVariable Long id) {
        return pickPack.pickList(id);
    }

    @PostMapping("/pick-lists/{id}/confirm")
    public PickPackService.PickList confirm(@PathVariable Long id, @RequestBody(required = false) List<PickPackService.Picked> picked) {
        return pickPack.confirm(id, picked);
    }

    public record Scan(String code, BigDecimal quantity) {}

    @PostMapping("/pick-lists/{id}/scan")
    public PickPackService.PickList scan(@PathVariable Long id, @RequestBody Scan s) {
        return pickPack.scan(id, s.code(), s.quantity());
    }

    @PostMapping("/pick-lists/{id}/cancel")
    public void cancel(@PathVariable Long id) {
        pickPack.cancel(id);
    }

    @GetMapping("/pick-lists/{id}/pdf")
    public ResponseEntity<byte[]> pickListPdf(@PathVariable Long id) {
        return pdf(pickPack.pickListPdf(id));
    }

    @GetMapping("/orders/{id}")
    public PickPackService.OrderWarehouse order(@PathVariable Long id) {
        return pickPack.order(id);
    }

    @PostMapping("/orders/{id}/pack")
    public PickPackService.OrderWarehouse pack(@PathVariable Long id, @RequestBody(required = false) List<PickPackService.Carton> cartons) {
        return pickPack.pack(id, cartons);
    }

    public record Ship(String carrier, String trackingNumber, Boolean emailCustomer) {}

    public record Shipped(PickPackService.OrderWarehouse order, String email) {}

    /**
     * Ships the packed cartons (with the courier booking's tracking when booked); then, when asked, e-mails the
     * tracking to the customer — after the shipment is saved, so a missing e-mail address never stops the shipping.
     */
    @PostMapping("/orders/{id}/ship")
    public Shipped ship(@PathVariable Long id, @RequestBody(required = false) Ship body) {
        PickPackService.OrderWarehouse w = pickPack.ship(id, body == null ? null : body.carrier(), body == null ? null : body.trackingNumber());
        String email = null;
        if (body != null && Boolean.TRUE.equals(body.emailCustomer())) {
            Long fulfilment = pickPack.lastShipment(id);
            try {
                MailService.Sent s = mail.send(new MailService.Request("TRACKING", fulfilment));
                email = "Tracking e-mailed to " + s.to();
            } catch (RuntimeException e) {
                email = "Tracking not e-mailed: " + e.getMessage();
            }
        }
        return new Shipped(w, email);
    }

    @GetMapping("/orders/{id}/despatch-note")
    public ResponseEntity<byte[]> despatch(@PathVariable Long id) {
        return pdf(pickPack.despatchNote(id));
    }

    private static ResponseEntity<byte[]> pdf(DocumentService.Pdf p) {
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.inline().filename(p.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(p.bytes());
    }
}
