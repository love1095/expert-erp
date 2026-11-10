package com.experterp.backend.controller;

import com.experterp.backend.service.CustomerService;
import com.experterp.backend.service.PaymentTermService;
import com.experterp.backend.service.PaymentTermService.Term;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Short pick lists for forms (names only, so any signed-in user may read them). Adding to a list ("+" next to the
 * field, like Cin7) needs a permission to edit sales, purchases or customers (PermissionFilter).
 */
@RestController
@RequestMapping("/api/lists")
@RequiredArgsConstructor
public class ListsController {

    private final PaymentTermService paymentTerms;
    private final CustomerService customers;

    public record NewTerm(String name, PaymentTermService.Method method, Integer days) {}

    public record NewName(String name) {}

    /** The payment terms to choose from, like Cin7's terms list, each with what it means for the due date. */
    @GetMapping("/payment-terms")
    public List<Term> paymentTerms() {
        return paymentTerms.list();
    }

    @PostMapping("/payment-terms")
    @ResponseStatus(HttpStatus.CREATED)
    public Term addPaymentTerm(@RequestBody NewTerm req) {
        return paymentTerms.add(req.name(), req.method(), req.days());
    }

    @PostMapping("/sales-reps")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> addSalesRep(@RequestBody NewName req) {
        return Map.of("name", customers.addSalesRep(req.name()));
    }
}
