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
    private final com.experterp.backend.service.PickListService pickLists;

    public record NewTerm(String name, PaymentTermService.Method method, Integer days) {}

    public record NewName(String name) {}

    public record Rename(String from, String to) {}

    static final String SETTINGS = "hasAnyRole('ADMIN', 'SUPER_ADMIN') or hasAuthority('PERM_SETTINGS')";

    // Settings → Organisation: the two lists with how often each entry is used; rename and remove (Settings only)
    @GetMapping("/payment-terms/manage")
    @org.springframework.security.access.prepost.PreAuthorize(SETTINGS)
    public List<com.experterp.backend.service.PickListService.Entry> managePaymentTerms() {
        return pickLists.paymentTerms();
    }

    @org.springframework.web.bind.annotation.PutMapping("/payment-terms/rename")
    @org.springframework.security.access.prepost.PreAuthorize(SETTINGS)
    public List<com.experterp.backend.service.PickListService.Entry> renamePaymentTerm(@RequestBody Rename req) {
        pickLists.renamePaymentTerm(req.from(), req.to());
        return pickLists.paymentTerms();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/payment-terms")
    @org.springframework.security.access.prepost.PreAuthorize(SETTINGS)
    public List<com.experterp.backend.service.PickListService.Entry> removePaymentTerm(@org.springframework.web.bind.annotation.RequestParam String name) {
        pickLists.removePaymentTerm(name);
        return pickLists.paymentTerms();
    }

    @GetMapping("/sales-reps/manage")
    @org.springframework.security.access.prepost.PreAuthorize(SETTINGS)
    public List<com.experterp.backend.service.PickListService.Entry> manageSalesReps() {
        return pickLists.salesReps();
    }

    @org.springframework.web.bind.annotation.PutMapping("/sales-reps/rename")
    @org.springframework.security.access.prepost.PreAuthorize(SETTINGS)
    public List<com.experterp.backend.service.PickListService.Entry> renameSalesRep(@RequestBody Rename req) {
        pickLists.renameSalesRep(req.from(), req.to());
        return pickLists.salesReps();
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/sales-reps")
    @org.springframework.security.access.prepost.PreAuthorize(SETTINGS)
    public List<com.experterp.backend.service.PickListService.Entry> removeSalesRep(@org.springframework.web.bind.annotation.RequestParam String name) {
        pickLists.removeSalesRep(name);
        return pickLists.salesReps();
    }

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
