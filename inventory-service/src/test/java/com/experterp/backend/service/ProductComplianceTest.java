package com.experterp.backend.service;

import com.experterp.backend.TestcontainersConfiguration;
import com.experterp.backend.entity.Product;
import com.experterp.backend.entity.Tenant;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import com.experterp.backend.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Phase P1 / P4: safety compliance per SKU (not inherited), certificate files, expiring certificates, CSV; product files. */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class ProductComplianceTest {

    static final byte[] PDF = "%PDF-1.4 test certificate".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};

    @Autowired ProductComplianceService compliance;
    @Autowired ProductFileService files;
    @Autowired ProductImportService importService;
    @Autowired ProductRepository productRepository;
    @Autowired TenantRepository tenantRepository;

    String id;
    Product glove8, glove9, boot;

    @BeforeEach
    void setUp() {
        id = UUID.randomUUID().toString().substring(0, 6);
        Tenant t = new Tenant();
        t.setName("Comply " + id);
        t.setSlug("comply-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        glove8 = product("GLOVE-8", "GLOVE");
        glove9 = product("GLOVE-9", "GLOVE");
        boot = product("BOOT-42", null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void complianceAndTheCertificateBelongToOneSkuAndExpiringCertificatesAreListed() {
        LocalDate today = LocalDate.now();
        compliance.save(glove8.getId(), new ProductComplianceService.Request("EN 388:2016+A1:2018; EN ISO 21420:2020", true, true, "Category II",
                "CE-2020-118", "SATRA (0321)", today.plusDays(20), null));
        files.upload(glove8.getId(), ProductFileService.Kind.CERTIFICATE, "cert-old.pdf", "application/pdf", PDF);
        files.upload(glove8.getId(), ProductFileService.Kind.CERTIFICATE, "cert.pdf", "application/pdf", PDF);   // replaces the old one

        ProductComplianceService.Compliance c = compliance.get(glove8.getId());
        assertThat(c.standards()).contains("EN 388");
        assertThat(c.ceMarked()).isTrue();
        assertThat(c.certificateFile().fileName()).isEqualTo("cert.pdf");
        assertThat(files.files(glove8.getId())).hasSize(1);

        // The other size of the same family has nothing: a certificate is never taken from another SKU
        ProductComplianceService.Compliance other = compliance.get(glove9.getId());
        assertThat(other.standards()).isNull();
        assertThat(other.certificateFile()).isNull();

        // Expiring within 30 days: the boot's (expired 5 days ago) first, then the glove's; the 90-day one is not listed
        compliance.save(boot.getId(), new ProductComplianceService.Request("EN ISO 20345:2022", false, true, "S3 SRC", "UK-77", null, today.minusDays(5), null));
        compliance.save(glove9.getId(), new ProductComplianceService.Request("EN 388", true, false, null, "CE-9", null, today.plusDays(90), null));
        List<ProductComplianceService.Expiring> soon = compliance.expiring(30);
        assertThat(soon).extracting(ProductComplianceService.Expiring::sku).containsExactly("BOOT-42-" + id, "GLOVE-8-" + id);
        assertThat(soon.get(0).daysLeft()).isEqualTo(-5);
        assertThat(soon.get(1).hasFile()).isTrue();
        assertThat(soon.get(0).hasFile()).isFalse();
    }

    @Test
    void theProductCsvCarriesComplianceBothWays() throws Exception {
        compliance.save(glove8.getId(), new ProductComplianceService.Request("EN 388", true, false, "Category II", "CE-1", "SATRA", LocalDate.of(2027, 3, 31), "kept"));
        StringWriter out = new StringWriter();
        importService.exportCsv(null, out);
        String csv = out.toString();
        assertThat(csv).contains("Standards,CEMarked,UKCAMarked,ProtectionClass,CertificateNumber,CertificateIssuer,CertificateExpiry");
        assertThat(csv).contains("EN 388,Yes,No,Category II,CE-1,SATRA,2027-03-31");

        // Import: a new SKU with its compliance, GLOVE-8's expiry changed (other columns absent: kept), a bad date warned
        String in = """
                ProductCode,Name,CertificateExpiry,UKCAMarked
                GLOVE-8-%1$s,Glove 8,31/12/2027,Yes
                VISOR-%1$s,Visor,2028-01-15,No
                BOOT-42-%1$s,Boot,soon,Yes
                """.formatted(id);
        ProductImportService.ImportResult r = importService.importCsv(new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)), false);
        assertThat(r.created()).isEqualTo(1);
        assertThat(r.warnings()).anyMatch(w -> w.contains("BOOT-42-" + id) && w.contains("not a date"));
        ProductComplianceService.Compliance g = compliance.get(glove8.getId());
        assertThat(g.certificateExpiry()).isEqualTo(LocalDate.of(2027, 12, 31));
        assertThat(g.ukcaMarked()).isTrue();
        assertThat(g.standards()).isEqualTo("EN 388");                      // not in the file: kept
        assertThat(g.notes()).isEqualTo("kept");
        Long visor = productRepository.findBySkuIn(List.of("VISOR-" + id)).get(0).getId();
        assertThat(compliance.get(visor).certificateExpiry()).isEqualTo(LocalDate.of(2028, 1, 15));
        assertThat(compliance.get(boot.getId()).ukcaMarked()).isTrue();
        assertThat(compliance.get(boot.getId()).certificateExpiry()).isNull();
    }

    @Test
    void imagesAndDocumentsAreCheckedOrderedAndKeptPerOrganisation() {
        assertThatThrownBy(() -> files.upload(boot.getId(), ProductFileService.Kind.CERTIFICATE, "fake.pdf", "application/pdf",
                "not really a pdf".getBytes(StandardCharsets.UTF_8))).hasMessageContaining("PDF");
        assertThatThrownBy(() -> files.upload(boot.getId(), ProductFileService.Kind.IMAGE, "x.png", "image/png", PDF)).hasMessageContaining("Images");
        ProductFileService.FileInfo front = files.upload(boot.getId(), ProductFileService.Kind.IMAGE, "front.png", "image/png", PNG);
        ProductFileService.FileInfo side = files.upload(boot.getId(), ProductFileService.Kind.IMAGE, "side.jpg", "application/octet-stream", JPEG);
        assertThat(side.contentType()).isEqualTo("image/jpeg");                // what the bytes are
        files.upload(boot.getId(), ProductFileService.Kind.DATASHEET, "datasheet.pdf", "application/pdf", PDF);
        files.upload(boot.getId(), ProductFileService.Kind.DECLARATION, "doc.pdf", "application/pdf", PDF);
        assertThat(files.images(boot.getId())).extracting(ProductFileService.FileInfo::fileName).containsExactly("front.png", "side.jpg");
        files.makeMain(side.id());
        assertThat(files.images(boot.getId())).extracting(ProductFileService.FileInfo::fileName).containsExactly("side.jpg", "front.png");
        files.delete(front.id());
        assertThat(files.files(boot.getId())).hasSize(3);
        assertThat(files.content(side.id()).bytes()).isEqualTo(JPEG);

        Tenant t = new Tenant();
        t.setName("Other " + id);
        t.setSlug("other-comply-" + id);
        TenantContext.set(tenantRepository.save(t).getId());
        assertThatThrownBy(() -> files.content(side.id())).hasMessageContaining("not found");
        assertThatThrownBy(() -> files.files(boot.getId())).hasMessageContaining("not found");
        assertThatThrownBy(() -> compliance.get(boot.getId())).hasMessageContaining("not found");
        assertThat(compliance.expiring(3650)).isEmpty();
    }

    private Product product(String sku, String family) {
        Product p = new Product();
        p.setSku(sku + "-" + id);
        p.setName(sku);
        p.setUnit("Each");
        if (family != null) {
            p.setFamilySku(family + "-" + id);
            p.setFamilyName(family);
        }
        return productRepository.save(p);
    }
}
