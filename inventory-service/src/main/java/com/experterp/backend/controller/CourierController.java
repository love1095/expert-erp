package com.experterp.backend.controller;

import com.experterp.backend.service.CourierService;
import com.experterp.backend.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Phase O: courier services and prices for an order's packed cartons, booking, the label, tracking, cancelling. */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class CourierController {

    private final CourierService couriers;

    @GetMapping("/orders/{id}/courier/services")
    public CourierService.Quote services(@PathVariable Long id) {
        return couriers.services(id);
    }

    @GetMapping("/orders/{id}/courier")
    public List<CourierService.Shipment> shipments(@PathVariable Long id) {
        return couriers.shipments(id);
    }

    public record Book(String serviceCode) {}

    @PostMapping("/orders/{id}/courier")
    public CourierService.Shipment book(@PathVariable Long id, @RequestBody Book b) {
        return couriers.book(id, b.serviceCode());
    }

    @GetMapping("/courier/{id}/label")
    public ResponseEntity<byte[]> label(@PathVariable Long id) {
        DocumentService.Pdf p = couriers.label(id);
        return ResponseEntity.ok().header("Content-Disposition", ContentDisposition.inline().filename(p.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(p.bytes());
    }

    @PostMapping("/courier/{id}/track")
    public CourierService.Shipment track(@PathVariable Long id) {
        return couriers.track(id);
    }

    @PostMapping("/courier/{id}/cancel")
    public CourierService.Shipment cancel(@PathVariable Long id) {
        return couriers.cancel(id);
    }
}
