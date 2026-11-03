package com.experterp.backend.service;

import com.experterp.backend.entity.CompanyDetails;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.CompanyDetailsRepository;
import com.experterp.backend.repository.TenantRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The organisation's details printed on its documents (phase D5): name, address, VAT number, bank details, logo. */
@Service
@RequiredArgsConstructor
public class CompanyDetailsService {

    static final int MAX_LOGO_BYTES = 512 * 1024;
    private static final Set<String> LOGO_TYPES = Set.of("image/png", "image/jpeg");

    private final CompanyDetailsRepository repository;
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public record Details(
            @Size(max = 255) String legalName,
            @Size(max = 1000) String address,
            @Size(max = 100) String phone,
            @Email @Size(max = 255) String email,
            @Size(max = 255) String website,
            @Size(max = 50) String vatNumber,
            @Size(max = 50) String companyNumber,
            @Size(max = 1000) String bankDetails,
            @Size(max = 2000) String documentFooter,
            boolean hasLogo) {}

    /** What the documents print: the saved details, the organisation's name when no legal name is set. */
    public record Printed(String name, Details details, byte[] logo, String logoContentType) {}

    @Transactional(readOnly = true)
    public Details get() {
        return repository.findFirstByOrderByIdAsc().map(CompanyDetailsService::toDetails)
                .orElse(new Details(null, null, null, null, null, null, null, null, null, false));
    }

    @Transactional
    public Details update(Details d) {
        CompanyDetails c = current();
        Map<String, Object> changes = new LinkedHashMap<>();
        set(changes, "legalName", c.getLegalName(), clean(d.legalName()), c::setLegalName);
        set(changes, "address", c.getAddress(), clean(d.address()), c::setAddress);
        set(changes, "phone", c.getPhone(), clean(d.phone()), c::setPhone);
        set(changes, "email", c.getEmail(), clean(d.email()), c::setEmail);
        set(changes, "website", c.getWebsite(), clean(d.website()), c::setWebsite);
        set(changes, "vatNumber", c.getVatNumber(), clean(d.vatNumber()), c::setVatNumber);
        set(changes, "companyNumber", c.getCompanyNumber(), clean(d.companyNumber()), c::setCompanyNumber);
        set(changes, "bankDetails", c.getBankDetails(), clean(d.bankDetails()), c::setBankDetails);
        set(changes, "documentFooter", c.getDocumentFooter(), clean(d.documentFooter()), c::setDocumentFooter);
        c.setUpdatedAt(LocalDateTime.now());
        CompanyDetails saved = repository.save(c);
        if (!changes.isEmpty()) auditService.log("COMPANY_DETAILS", saved.getId(), "UPDATE", changes);
        return toDetails(saved);
    }

    @Transactional
    public Details setLogo(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("Choose an image");
        if (bytes.length > MAX_LOGO_BYTES) throw new IllegalArgumentException("The logo must be 512 KB or less");
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (!LOGO_TYPES.contains(type) || !looksLike(bytes, type)) throw new IllegalArgumentException("The logo must be a PNG or JPEG image");
        CompanyDetails c = current();
        c.setLogo(bytes);
        c.setLogoContentType(type);
        c.setUpdatedAt(LocalDateTime.now());
        CompanyDetails saved = repository.save(c);
        auditService.log("COMPANY_DETAILS", saved.getId(), "LOGO", Map.of("bytes", bytes.length, "type", type));
        return toDetails(saved);
    }

    @Transactional
    public Details removeLogo() {
        CompanyDetails c = current();
        c.setLogo(null);
        c.setLogoContentType(null);
        c.setUpdatedAt(LocalDateTime.now());
        CompanyDetails saved = repository.save(c);
        auditService.log("COMPANY_DETAILS", saved.getId(), "LOGO", Map.of("removed", true));
        return toDetails(saved);
    }

    @Transactional(readOnly = true)
    public Printed printed() {
        CompanyDetails c = repository.findFirstByOrderByIdAsc().orElse(null);
        String orgName = tenantRepository.findById(TenantContext.get()).map(Tenant::getName).orElse("");
        if (c == null) return new Printed(orgName, get(), null, null);
        return new Printed(c.getLegalName() != null ? c.getLegalName() : orgName, toDetails(c), c.getLogo(), c.getLogoContentType());
    }

    // ---- private ----

    private CompanyDetails current() {
        return repository.findFirstByOrderByIdAsc().orElseGet(CompanyDetails::new);
    }

    private static boolean looksLike(byte[] b, String type) {
        if (type.equals("image/png")) return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    private static void set(Map<String, Object> changes, String field, String before, String after, java.util.function.Consumer<String> setter) {
        if (!Objects.equals(before, after)) {
            changes.put(field, after == null ? "" : after);
            setter.accept(after);
        }
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.replace("\r\n", "\n").strip();
        return t.isEmpty() ? null : t;
    }

    private static Details toDetails(CompanyDetails c) {
        return new Details(c.getLegalName(), c.getAddress(), c.getPhone(), c.getEmail(), c.getWebsite(), c.getVatNumber(),
                c.getCompanyNumber(), c.getBankDetails(), c.getDocumentFooter(), c.getLogo() != null);
    }
}
