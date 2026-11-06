package com.experterp.backend.service;

import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Phase P1 / P4: a product's files — images (the first is the main one, pushed to Shopify), datasheets, declarations
 * of conformity, certificates — kept in the database with the product, per SKU (never shared across a family).
 */
@Service
@RequiredArgsConstructor
public class ProductFileService {

    public enum Kind { IMAGE, DATASHEET, DECLARATION, CERTIFICATE, OTHER }

    static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final Set<String> IMAGES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final Set<String> DOCUMENTS = Set.of("application/pdf", "image/jpeg", "image/png");

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final AuditService auditService;

    public record FileInfo(Long id, Long productId, Kind kind, String fileName, String contentType, int sizeBytes, int position,
                           LocalDateTime createdAt, String createdBy) {}

    public record Content(String fileName, String contentType, byte[] bytes) {}

    @Transactional(readOnly = true)
    public List<FileInfo> files(Long productId) {
        product(productId);
        return jdbc.query("""
                SELECT id, product_id, kind, file_name, content_type, size_bytes, position, created_at, created_by FROM product_files
                 WHERE tenant_id = ? AND product_id = ? ORDER BY kind, position, id""", (rs, n) -> new FileInfo(rs.getLong(1), rs.getLong(2),
                Kind.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getInt(6), rs.getInt(7), rs.getTimestamp(8).toLocalDateTime(),
                rs.getString(9)), TenantContext.get(), productId);
    }

    /** Adds a file; a certificate replaces the product's previous one (only the current certificate is kept). */
    @Transactional
    public FileInfo upload(Long productId, Kind kind, String fileName, String contentType, byte[] bytes) {
        product(productId);
        if (kind == null) throw new IllegalArgumentException("Say what the file is");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("The file is empty");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Files up to 10 MB");
        String type = sniff(bytes, contentType);
        if (kind == Kind.IMAGE ? !IMAGES.contains(type) : !DOCUMENTS.contains(type)) {
            throw new IllegalArgumentException(kind == Kind.IMAGE ? "Images: JPEG, PNG, WebP or GIF" : "Documents: PDF, or a JPEG / PNG scan");
        }
        Long t = TenantContext.get();
        if (kind == Kind.CERTIFICATE) jdbc.update("DELETE FROM product_files WHERE tenant_id = ? AND product_id = ? AND kind = 'CERTIFICATE'", t, productId);
        Integer next = jdbc.queryForObject("SELECT COALESCE(MAX(position), -1) + 1 FROM product_files WHERE tenant_id = ? AND product_id = ? AND kind = ?",
                Integer.class, t, productId, kind.name());
        String name = fileName == null || fileName.isBlank() ? kind.name().toLowerCase(Locale.ROOT) : fileName.replaceAll("[\\\\/\\r\\n]", "_");
        Long id = jdbc.queryForObject("""
                INSERT INTO product_files (tenant_id, product_id, kind, file_name, content_type, size_bytes, content, position, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Long.class, t, productId, kind.name(), name.length() > 255 ? name.substring(0, 255) : name,
                type, bytes.length, bytes, next, auditService.currentActorName());
        auditService.log("PRODUCT", productId, "FILE_ADDED", Map.of("kind", kind.name(), "file", name));
        return info(id);
    }

    @Transactional(readOnly = true)
    public Content content(Long fileId) {
        return jdbc.query("SELECT file_name, content_type, content FROM product_files WHERE tenant_id = ? AND id = ?", rs -> {
            if (!rs.next()) throw new IllegalArgumentException("File not found");
            return new Content(rs.getString(1), rs.getString(2), rs.getBytes(3));
        }, TenantContext.get(), fileId);
    }

    /** The current certificate of a product, if any. */
    @Transactional(readOnly = true)
    public Optional<FileInfo> certificate(Long productId) {
        return files(productId).stream().filter(f -> f.kind() == Kind.CERTIFICATE).findFirst();
    }

    @Transactional
    public void delete(Long fileId) {
        FileInfo f = info(fileId);
        jdbc.update("DELETE FROM product_files WHERE tenant_id = ? AND id = ?", TenantContext.get(), fileId);
        auditService.log("PRODUCT", f.productId(), "FILE_DELETED", Map.of("kind", f.kind().name(), "file", f.fileName()));
    }

    /** Makes an image the main one (first), the others keeping their order. */
    @Transactional
    public List<FileInfo> makeMain(Long fileId) {
        FileInfo f = info(fileId);
        if (f.kind() != Kind.IMAGE) throw new IllegalArgumentException("Only an image can be the main image");
        List<Long> order = new ArrayList<>(jdbc.queryForList("SELECT id FROM product_files WHERE tenant_id = ? AND product_id = ? AND kind = 'IMAGE' ORDER BY position, id",
                Long.class, TenantContext.get(), f.productId()));
        order.remove(fileId);
        order.add(0, fileId);
        for (int i = 0; i < order.size(); i++) jdbc.update("UPDATE product_files SET position = ? WHERE id = ?", i, order.get(i));
        return files(f.productId());
    }

    /** The product's images, main first (for the Shopify sync). */
    @Transactional(readOnly = true)
    public List<FileInfo> images(Long productId) {
        return files(productId).stream().filter(f -> f.kind() == Kind.IMAGE).toList();
    }

    private FileInfo info(Long id) {
        return jdbc.query("""
                SELECT id, product_id, kind, file_name, content_type, size_bytes, position, created_at, created_by FROM product_files WHERE tenant_id = ? AND id = ?""",
                rs -> {
                    if (!rs.next()) throw new IllegalArgumentException("File not found");
                    return new FileInfo(rs.getLong(1), rs.getLong(2), Kind.valueOf(rs.getString(3)), rs.getString(4), rs.getString(5), rs.getInt(6),
                            rs.getInt(7), rs.getTimestamp(8).toLocalDateTime(), rs.getString(9));
                }, TenantContext.get(), id);
    }

    private void product(Long productId) {
        products.findById(productId).orElseThrow(() -> new IllegalArgumentException("Product not found"));
    }

    // What the bytes are, whatever the browser said (a renamed file is caught)
    static String sniff(byte[] b, String declared) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "application/pdf";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return "application/octet-stream";   // not a type we keep, whatever the browser said
    }
}
