package com.experterp.backend.controller;

import com.experterp.backend.service.DocumentService;
import com.experterp.backend.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Phase M: e-mail — settings, templates, compose and send (one, bulk statements, overdue reminders), outbox and logs. */
@RestController
@RequestMapping("/api/mail")
@RequiredArgsConstructor
public class MailController {

    private final MailService mail;

    @GetMapping("/settings")
    public MailService.Settings settings() {
        return mail.settings();
    }

    @PutMapping("/settings")
    public MailService.Settings updateSettings(@RequestBody MailService.SettingsRequest req) {
        return mail.updateSettings(req);
    }

    @GetMapping("/templates")
    public List<MailService.Template> templates() {
        return mail.templates();
    }

    @PutMapping("/templates/{type}")
    public MailService.Template saveTemplate(@PathVariable MailService.DocType type, @RequestBody Map<String, String> body) {
        return mail.saveTemplate(type, body.get("subject"), body.get("body"));
    }

    @DeleteMapping("/templates/{type}")
    public MailService.Template resetTemplate(@PathVariable MailService.DocType type) {
        return mail.resetTemplate(type);
    }

    @PostMapping("/preview")
    public MailService.Draft preview(@RequestBody MailService.Request req) {
        return mail.preview(req);
    }

    @PostMapping("/send")
    public MailService.Sent send(@RequestBody MailService.Request req) {
        return mail.send(req);
    }

    public record BulkStatements(List<Long> customerIds, String type, LocalDate from, LocalDate to) {}

    @PostMapping("/statements")
    public List<MailService.BulkResult> statements(@RequestBody BulkStatements req) {
        return mail.sendStatements(req.customerIds(), req.type(), req.from(), req.to());
    }

    /** No customers given: every customer with an overdue invoice. */
    @PostMapping("/reminders")
    public List<MailService.BulkResult> reminders(@RequestBody(required = false) Map<String, List<Long>> body) {
        return mail.sendReminders(body == null ? null : body.get("customerIds"));
    }

    @GetMapping("/outbox")
    public List<MailService.Sent> outbox(@RequestParam(required = false) String docKey, @RequestParam(defaultValue = "200") int limit) {
        return mail.outbox(docKey, limit, null);
    }

    @GetMapping("/outbox/{id}/attachment")
    public ResponseEntity<byte[]> attachment(@PathVariable Long id) {
        DocumentService.Pdf pdf = mail.attachment(id);
        return ResponseEntity.ok()
                .header("Content-Disposition", ContentDisposition.inline().filename(pdf.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_PDF).body(pdf.bytes());
    }
}
