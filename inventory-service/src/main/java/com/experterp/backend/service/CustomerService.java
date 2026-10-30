package com.experterp.backend.service;

import com.experterp.backend.dto.CustomerRequest;
import com.experterp.backend.dto.CustomerResponse;
import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerContact;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.SaleOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final SaleOrderRepository saleOrderRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Page<CustomerResponse> findAll(String search, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by("name").and(Sort.by("id")));
        Page<Customer> customers = (search == null || search.isBlank())
                ? customerRepository.findAll(pageable)
                : customerRepository.search(search.trim(), pageable);

        List<Long> ids = customers.getContent().stream().map(Customer::getId).toList();
        Map<Long, Long> counts = ids.isEmpty() ? Map.of() : saleOrderRepository.countByCustomerIds(ids).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
        return customers.map(c -> toResponse(c, counts.getOrDefault(c.getId(), 0L)));
    }

    @Transactional(readOnly = true)
    public CustomerResponse findById(Long id) {
        return toResponse(getCustomer(id), saleOrderRepository.countByCustomerId(id));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest req) {
        Customer c = new Customer();
        apply(c, req);
        customerRepository.save(c);
        auditService.log("CUSTOMER", c.getId(), "CREATE", Map.of("name", c.getName()));
        return toResponse(c, 0);
    }

    @Transactional
    public CustomerResponse update(Long id, CustomerRequest req) {
        Customer c = getCustomer(id);
        Map<String, Object> changes = new HashMap<>();
        if (!c.getName().equals(req.getName().trim())) changes.put("name", req.getName().trim());
        if (req.getStatus() != null && req.getStatus() != c.getStatus()) changes.put("status", req.getStatus());
        apply(c, req);
        c.setUpdatedAt(LocalDateTime.now());
        auditService.log("CUSTOMER", id, "UPDATE", changes);
        return toResponse(c, saleOrderRepository.countByCustomerId(id));
    }

    /** Customers with orders are kept: the orders must still say who they were for. */
    @Transactional
    public void delete(Long id) {
        Customer c = getCustomer(id);
        long orders = saleOrderRepository.countByCustomerId(id);
        if (orders > 0) {
            throw new IllegalStateException("Customer has " + orders + " sale order(s) and cannot be deleted; set it to Deprecated instead");
        }
        customerRepository.delete(c);
        auditService.log("CUSTOMER", id, "DELETE", Map.of("name", c.getName()));
    }

    public Customer getCustomer(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    /**
     * The customer for a new sale order: the given id, or else the oldest customer with that exact
     * name (ignoring case), or else a new customer with that name.
     */
    @Transactional
    public Customer resolveForOrder(Long customerId, String customerName) {
        if (customerId != null) return getCustomer(customerId);
        String name = customerName.trim();
        return customerRepository.findFirstByNameIgnoreCaseOrderByIdAsc(name).orElseGet(() -> {
            Customer c = new Customer();
            c.setName(name);
            customerRepository.save(c);
            auditService.log("CUSTOMER", c.getId(), "CREATE", Map.of("name", name, "via", "sale order"));
            return c;
        });
    }

    /** An existing customer with this name (any case), without creating one. */
    public Optional<Customer> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return customerRepository.findFirstByNameIgnoreCaseOrderByIdAsc(name.trim());
    }

    /** Copies every field of the request onto the customer; contacts are replaced as a whole. */
    void apply(Customer c, CustomerRequest req) {
        c.setName(req.getName().trim());
        if (req.getStatus() != null) c.setStatus(req.getStatus());
        c.setCurrency(blankToNull(req.getCurrency()));
        c.setPaymentTerm(blankToNull(req.getPaymentTerm()));
        c.setTaxRule(blankToNull(req.getTaxRule()));
        c.setTaxNumber(blankToNull(req.getTaxNumber()));
        c.setPriceTier(blankToNull(req.getPriceTier()));
        c.setDiscount(req.getDiscount());
        c.setCreditLimit(req.getCreditLimit());
        c.setAccountReceivable(blankToNull(req.getAccountReceivable()));
        c.setSaleAccount(blankToNull(req.getSaleAccount()));
        c.setCarrier(blankToNull(req.getCarrier()));
        c.setSalesRepresentative(blankToNull(req.getSalesRepresentative()));
        c.setLocation(blankToNull(req.getLocation()));
        c.setTags(blankToNull(req.getTags()));
        c.setAddress(blankToNull(req.getAddress()));
        c.setNotes(blankToNull(req.getNotes()));

        List<CustomerContact> contacts = req.getContacts() == null ? List.of() : req.getContacts().stream()
                .map(CustomerService::toContact)
                .filter(CustomerService::hasDetails)
                .collect(Collectors.toList());
        // One default contact: the first one marked default, else the first one
        boolean seenDefault = false;
        for (CustomerContact cc : contacts) {
            if (cc.isDefaultContact() && !seenDefault) seenDefault = true;
            else cc.setDefaultContact(false);
        }
        if (!seenDefault && !contacts.isEmpty()) contacts.get(0).setDefaultContact(true);
        // Same order as @OrderBy gives when loaded: default first, then as given
        contacts.sort(Comparator.comparing(cc -> !cc.isDefaultContact()));
        c.replaceContacts(contacts);
    }

    private static CustomerContact toContact(CustomerRequest.Contact r) {
        CustomerContact cc = new CustomerContact();
        cc.setName(blankToNull(r.getName()));
        cc.setJobTitle(blankToNull(r.getJobTitle()));
        cc.setPhone(blankToNull(r.getPhone()));
        cc.setMobilePhone(blankToNull(r.getMobilePhone()));
        cc.setFax(blankToNull(r.getFax()));
        cc.setEmail(blankToNull(r.getEmail()));
        cc.setWebsite(blankToNull(r.getWebsite()));
        cc.setComment(blankToNull(r.getComment()));
        cc.setDefaultContact(r.isDefaultContact());
        cc.setIncludeInEmail(r.isIncludeInEmail());
        if (r.getMarketingConsent() != null) cc.setMarketingConsent(r.getMarketingConsent());
        return cc;
    }

    private static boolean hasDetails(CustomerContact cc) {
        return cc.getName() != null || cc.getEmail() != null || cc.getPhone() != null || cc.getMobilePhone() != null;
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private CustomerResponse toResponse(Customer c, long orderCount) {
        CustomerResponse r = new CustomerResponse();
        r.setId(c.getId());
        r.setName(c.getName());
        r.setStatus(c.getStatus());
        r.setCurrency(c.getCurrency());
        r.setPaymentTerm(c.getPaymentTerm());
        r.setTaxRule(c.getTaxRule());
        r.setTaxNumber(c.getTaxNumber());
        r.setPriceTier(c.getPriceTier());
        r.setDiscount(c.getDiscount());
        r.setCreditLimit(c.getCreditLimit());
        r.setAccountReceivable(c.getAccountReceivable());
        r.setSaleAccount(c.getSaleAccount());
        r.setCarrier(c.getCarrier());
        r.setSalesRepresentative(c.getSalesRepresentative());
        r.setLocation(c.getLocation());
        r.setTags(c.getTags());
        r.setAddress(c.getAddress());
        r.setNotes(c.getNotes());
        r.setContacts(c.getContacts().stream().map(cc -> {
            CustomerResponse.Contact x = new CustomerResponse.Contact();
            x.setId(cc.getId());
            x.setName(cc.getName());
            x.setJobTitle(cc.getJobTitle());
            x.setPhone(cc.getPhone());
            x.setMobilePhone(cc.getMobilePhone());
            x.setFax(cc.getFax());
            x.setEmail(cc.getEmail());
            x.setWebsite(cc.getWebsite());
            x.setComment(cc.getComment());
            x.setDefaultContact(cc.isDefaultContact());
            x.setIncludeInEmail(cc.isIncludeInEmail());
            x.setMarketingConsent(cc.getMarketingConsent());
            return x;
        }).toList());
        r.setSource(c.getSource());
        r.setExternalId(c.getExternalId());
        r.setOrderCount(orderCount);
        r.setCreatedAt(c.getCreatedAt());
        r.setUpdatedAt(c.getUpdatedAt());
        return r;
    }
}
