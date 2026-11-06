package com.experterp.backend.controller;

import com.experterp.backend.service.BankService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Phase K3: bank accounts, statement import, reconciliation, spend / receive money and transfers (like Xero). */
@RestController
@RequestMapping("/api/bank")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('PERM_ACCOUNTING')")
public class BankController {

    private final BankService bank;

    @GetMapping("/accounts")
    public List<BankService.BankAccount> accounts() {
        return bank.accounts();
    }

    @PostMapping(value = "/accounts/{code}/statements", consumes = "multipart/form-data")
    public BankService.ImportResult importStatement(@PathVariable String code, @RequestParam("file") MultipartFile file,
                                                    @RequestParam(defaultValue = "false") boolean apply) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("The file is empty");
        try (var in = file.getInputStream()) {
            return bank.importStatement(code, in, file.getOriginalFilename(), apply);
        }
    }

    @GetMapping("/accounts/{code}/lines")
    public List<BankService.StatementLine> lines(@PathVariable String code, @RequestParam(defaultValue = "UNRECONCILED") String status) {
        return bank.lines(code, status);
    }

    @GetMapping("/accounts/{code}/unmatched")
    public List<BankService.Item> unmatched(@PathVariable String code) {
        return bank.unmatched(code);
    }

    @PostMapping("/lines/{id}/match")
    public BankService.StatementLine match(@PathVariable Long id, @RequestBody Map<String, List<String>> body) {
        return bank.match(id, body.get("sourceKeys"));
    }

    @PostMapping("/lines/{id}/create")
    public BankService.StatementLine create(@PathVariable Long id, @RequestBody BankService.CreateRequest req) {
        return bank.create(id, req);
    }

    @PostMapping("/lines/{id}/transfer")
    public BankService.StatementLine transfer(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return bank.transfer(id, body.get("accountCode"), body.get("reference"));
    }

    @PostMapping("/lines/{id}/unreconcile")
    public void unreconcile(@PathVariable Long id) {
        bank.unreconcile(id);
    }

    @DeleteMapping("/lines/{id}")
    public void deleteLine(@PathVariable Long id) {
        bank.deleteLine(id);
    }

    @GetMapping("/transactions")
    public List<BankService.Transaction> transactions(@RequestParam(required = false) String account) {
        return bank.transactions(account);
    }

    @PostMapping("/transactions")
    public BankService.Transaction createTransaction(@RequestBody BankService.TransactionRequest req) {
        return bank.createTransaction(req);
    }

    @PostMapping("/transactions/{id}/void")
    public void voidTransaction(@PathVariable Long id) {
        bank.voidTransaction(id);
    }
}
