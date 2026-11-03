package com.experterp.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.time.LocalDateTime;

/** The organisation's details printed on its documents (phase D5). One per organisation. */
@Entity
@Table(name = "company_details")
@Getter
@Setter
@NoArgsConstructor
public class CompanyDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "legal_name")
    private String legalName;

    @Column(columnDefinition = "TEXT")
    private String address;

    private String phone;
    private String email;
    private String website;

    @Column(name = "vat_number")
    private String vatNumber;

    @Column(name = "company_number")
    private String companyNumber;

    @Column(name = "bank_details", columnDefinition = "TEXT")
    private String bankDetails;

    @Column(name = "document_footer", columnDefinition = "TEXT")
    private String documentFooter;

    @Basic(fetch = FetchType.LAZY)
    @Column(name = "logo")
    private byte[] logo;

    @Column(name = "logo_content_type")
    private String logoContentType;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
