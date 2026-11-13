package com.experterp.backend.service;

import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cin7's "Save as preset" / "Select a preset" on a new purchase: a named copy of the header (supplier, contact, vendor
 * address, terms, tax rule, approach, location, blind receipt, ship-to company and address, comments). The lines and the
 * dates are not kept: a preset starts an order, it is not a copy of one. The header is kept as the form sends it (a JSON
 * object), so the form fills itself from it. Saving under a name already used replaces that preset.
 */
@Service
@RequiredArgsConstructor
public class PurchasePresetService {

    // What a preset may hold: anything else the form sends is dropped
    static final List<String> FIELDS = List.of("supplierName", "contactName", "contactPhone", "vendorAddress", "supplierReference",
            "paymentTerm", "taxRule", "approach", "warehouseId", "blindReceipt", "shipToCompany", "shippingAddress", "notes");

    public record Preset(Long id, String name, JsonNode data, String createdBy, LocalDateTime updatedAt) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<Preset> list() {
        return jdbc.query("SELECT id, name, data, created_by, updated_at FROM purchase_presets WHERE tenant_id = ? ORDER BY lower(name)",
                (rs, n) -> new Preset(rs.getLong("id"), rs.getString("name"), read(rs.getString("data")), rs.getString("created_by"),
                        rs.getTimestamp("updated_at").toLocalDateTime()),
                TenantContext.get());
    }

    /** Saves the header under this name; a preset of the same name (ignoring case) is replaced. */
    @Transactional
    public Preset save(String name, Map<String, Object> data) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Enter a name for the preset");
        if (n.length() > 100) throw new IllegalArgumentException("The name is too long (100 characters at most)");
        if (data == null) throw new IllegalArgumentException("Nothing to save in the preset");
        Map<String, Object> kept = new java.util.LinkedHashMap<>();
        for (String f : FIELDS) {
            Object v = data.get(f);
            if (v != null && !(v instanceof String s && s.isBlank())) kept.put(f, v);
        }
        if (kept.isEmpty()) throw new IllegalArgumentException("Fill in the purchase's details before saving them as a preset");
        String text;
        try {
            text = json.writeValueAsString(kept);
        } catch (Exception e) {
            throw new IllegalArgumentException("The preset could not be read");
        }
        Long t = TenantContext.get();
        String by = auditService.currentActorName();
        int updated = jdbc.update("UPDATE purchase_presets SET name = ?, data = ?, created_by = ?, updated_at = now() WHERE tenant_id = ? AND lower(name) = lower(?)",
                n, text, by, t, n);
        if (updated == 0) {
            jdbc.update("INSERT INTO purchase_presets (tenant_id, name, data, created_by) VALUES (?, ?, ?, ?)", t, n, text, by);
        }
        Preset saved = list().stream().filter(p -> p.name().equalsIgnoreCase(n)).findFirst().orElseThrow();
        auditService.log("PURCHASE_PRESET", saved.id(), updated == 0 ? "CREATE" : "UPDATE", Map.of("name", n));
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        List<String> names = jdbc.queryForList("SELECT name FROM purchase_presets WHERE tenant_id = ? AND id = ?", String.class, TenantContext.get(), id);
        if (names.isEmpty()) throw new ResourceNotFoundException("Preset not found: " + id);
        jdbc.update("DELETE FROM purchase_presets WHERE tenant_id = ? AND id = ?", TenantContext.get(), id);
        auditService.log("PURCHASE_PRESET", id, "DELETE", Map.of("name", names.get(0)));
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }
}
