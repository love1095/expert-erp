package com.experterp.backend.service;

import com.experterp.backend.entity.Customer;
import com.experterp.backend.entity.CustomerPrice;
import com.experterp.backend.entity.PriceTier;
import com.experterp.backend.entity.Product;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.repository.CustomerPriceRepository;
import com.experterp.backend.repository.CustomerRepository;
import com.experterp.backend.repository.PriceTierRepository;
import com.experterp.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Prices for customers (phase G1, spec §5). The price an order line gets when none is typed:
 * <ol>
 *   <li>the customer's <b>contract price</b> for the SKU, valid on the order date;</li>
 *   <li>else the customer's <b>price tier</b>: the product's price for that tier, or the tier's percentage off
 *       tier 1 — a product without a price for the tier gets tier 1;</li>
 *   <li>else tier 1 (Retail).</li>
 * </ol>
 * Tiers are named per organisation; customers name their tier as Cin7 does (e.g. "Contract").
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final PriceTierRepository tierRepository;
    private final CustomerPriceRepository priceRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    /** A line's price and where it came from, e.g. (12.50, "Contract tier"). Null price = the product has none. */
    public record Price(BigDecimal unitPrice, String source, boolean contract) {}

    public record TierView(int number, String name, BigDecimal discountPercent, long customers) {}

    public record TierRequest(Integer number, String name, BigDecimal discountPercent) {}

    public record ContractPriceView(Long id, Long productId, String sku, String productName, BigDecimal unitPrice,
                                    BigDecimal tierPrice, LocalDate validFrom, LocalDate validTo, String note,
                                    String createdBy, boolean current) {}

    public record ContractPriceRequest(Long productId, BigDecimal unitPrice, LocalDate validFrom, LocalDate validTo, String note) {}

    // ---- tiers ----

    @Transactional
    public List<TierView> tiers() {
        List<PriceTier> tiers = ensureTiers();
        Map<String, Long> counts = new HashMap<>();
        jdbc.query("SELECT lower(price_tier) AS t, count(*) AS n FROM customers WHERE tenant_id = ? AND price_tier IS NOT NULL GROUP BY 1",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> counts.put(rs.getString("t"), rs.getLong("n")),
                com.experterp.backend.multitenancy.TenantContext.get());
        return tiers.stream().map(t -> new TierView(t.getNumber(), t.getName(), t.getDiscountPercent(),
                counts.getOrDefault(t.getName().toLowerCase(Locale.ROOT), 0L))).toList();
    }

    /** Replaces the tier list. Renaming a tier renames it on its customers; a tier customers use cannot go. */
    @Transactional
    public List<TierView> updateTiers(List<TierRequest> requested) {
        if (requested == null || requested.isEmpty()) throw new IllegalArgumentException("Keep at least tier 1");
        Map<Integer, TierRequest> byNumber = new TreeMap<>();
        Set<String> names = new HashSet<>();
        for (TierRequest r : requested) {
            if (r.number() == null || r.number() < 1 || r.number() > 10) throw new IllegalArgumentException("Tiers are numbered 1 to 10");
            String name = r.name() == null ? "" : r.name().trim();
            if (name.isEmpty() || name.length() > 100) throw new IllegalArgumentException("Tier " + r.number() + " needs a name (up to 100 characters)");
            if (!names.add(name.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Two tiers are called " + name);
            if (byNumber.put(r.number(), r) != null) throw new IllegalArgumentException("Tier " + r.number() + " is there twice");
            BigDecimal d = r.discountPercent();
            if (d != null && (d.signum() <= 0 || d.compareTo(HUNDRED) >= 0)) throw new IllegalArgumentException("A tier's discount is between 0 and 100 %");
            if (r.number() == 1 && d != null) throw new IllegalArgumentException("Tier 1 is the base price: it has no discount");
            if (r.number() > 4 && d == null) throw new IllegalArgumentException("Tier " + r.number() + " has no product prices: give it a discount off tier 1");
        }
        if (!byNumber.containsKey(1)) throw new IllegalArgumentException("Keep tier 1: it is the base price");

        Map<Integer, PriceTier> existing = new HashMap<>();
        ensureTiers().forEach(t -> existing.put((int) t.getNumber(), t));
        Map<String, Object> changes = new LinkedHashMap<>();
        for (PriceTier old : existing.values()) {
            TierRequest r = byNumber.get((int) old.getNumber());
            if (r == null) {
                long users = customersOn(old.getName());
                if (users > 0) throw new IllegalStateException(users + " customer(s) are on " + old.getName() + ": move them to another tier first");
                tierRepository.delete(old);
                changes.put("tier " + old.getNumber(), "removed " + old.getName());
            }
        }
        tierRepository.flush();
        for (Map.Entry<Integer, TierRequest> e : byNumber.entrySet()) {
            TierRequest r = e.getValue();
            PriceTier t = existing.get(e.getKey());
            String name = r.name().trim();
            if (t == null) {
                t = new PriceTier();
                t.setNumber(e.getKey().shortValue());
                changes.put("tier " + e.getKey(), "added " + name);
            } else if (!t.getName().equals(name)) {
                // Customers follow the new name
                jdbc.update("UPDATE customers SET price_tier = ? WHERE tenant_id = ? AND lower(price_tier) = lower(?)",
                        name, com.experterp.backend.multitenancy.TenantContext.get(), t.getName());
                changes.put("tier " + e.getKey(), t.getName() + " → " + name);
            }
            if (t.getId() != null && !Objects.equals(t.getDiscountPercent(), r.discountPercent())) {
                changes.put("tier " + e.getKey() + " discount", t.getDiscountPercent() + " → " + r.discountPercent());
            }
            t.setName(name);
            t.setDiscountPercent(r.discountPercent());
            tierRepository.save(t);
        }
        if (!changes.isEmpty()) auditService.log("PRICE_TIERS", null, "UPDATE", changes);
        return tiers();
    }

    // ---- prices ----

    /** The price of one product for a customer on a day (customer may be null: tier 1). */
    @Transactional
    public Price price(Customer customer, Product product, LocalDate day) {
        return prices(customer, List.of(product), day).get(product.getId());
    }

    @Transactional
    public Map<Long, Price> prices(Customer customer, Collection<Product> products, LocalDate day) {
        return prices(customer, products, day, null);
    }

    /** As above, at a tier chosen on the order (null = the customer's); the customer's contract prices still win. */
    @Transactional
    public Map<Long, Price> prices(Customer customer, Collection<Product> products, LocalDate day, String tierName) {
        Map<Long, Price> out = new HashMap<>();
        if (products.isEmpty()) return out;
        Map<Long, CustomerPrice> contracts = new HashMap<>();
        if (customer != null && customer.getId() != null) {
            for (CustomerPrice cp : priceRepository.validOn(customer.getId(), products.stream().map(Product::getId).toList(), day)) {
                // Two periods valid at once cannot be saved, but the latest start wins if they ever are
                contracts.merge(cp.getProduct().getId(), cp, (a, b) -> a.getValidFrom().isAfter(b.getValidFrom()) ? a : b);
            }
        }
        PriceTier tier = tierFor(customer, tierName);
        for (Product p : products) {
            CustomerPrice cp = contracts.get(p.getId());
            if (cp != null) {
                out.put(p.getId(), new Price(cp.getUnitPrice(), "Contract price", true));
                continue;
            }
            out.put(p.getId(), new Price(tierPrice(p, tier), tier.getName() + (tier.getNumber() == 1 ? "" : " tier"), false));
        }
        return out;
    }

    /** The product's price for a tier: its own price for that tier, the tier's discount off tier 1, else tier 1. */
    static BigDecimal tierPrice(Product p, PriceTier tier) {
        BigDecimal base = p.getSellPrice();
        if (tier.getDiscountPercent() != null) {
            return base == null ? null : base.multiply(HUNDRED.subtract(tier.getDiscountPercent()))
                    .divide(HUNDRED, 4, RoundingMode.HALF_UP);
        }
        BigDecimal own = switch (tier.getNumber()) {
            case 2 -> p.getTradePrice();
            case 3 -> p.getDetails() == null ? null : p.getDetails().getPriceTier3();
            case 4 -> p.getDetails() == null ? null : p.getDetails().getPriceTier4();
            default -> base;
        };
        return own != null && own.signum() > 0 ? own : base;
    }

    /** The tier chosen on an order (must be one of the organisation's), else the customer's. */
    @Transactional
    public PriceTier tierFor(Customer customer, String chosen) {
        if (chosen == null || chosen.isBlank()) return tierOf(customer);
        return ensureTiers().stream().filter(t -> t.getName().equalsIgnoreCase(chosen.trim())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("There is no price tier called " + chosen.trim()));
    }

    /** The customer's tier by name; tier 1 when none is set or the name is not a tier. */
    PriceTier tierOf(Customer customer) {
        List<PriceTier> tiers = ensureTiers();
        PriceTier first = tiers.get(0);
        if (customer == null || customer.getPriceTier() == null) return first;
        return tiers.stream().filter(t -> t.getName().equalsIgnoreCase(customer.getPriceTier().trim())).findFirst().orElse(first);
    }

    // ---- contract prices ----

    @Transactional
    public List<ContractPriceView> contractPrices(Long customerId) {
        Customer c = customer(customerId);
        PriceTier tier = tierOf(c);
        LocalDate today = LocalDate.now();
        return priceRepository.forCustomer(customerId).stream().map(cp -> view(cp, tier, today)).toList();
    }

    @Transactional
    public ContractPriceView addContractPrice(Long customerId, ContractPriceRequest req) {
        Customer c = customer(customerId);
        CustomerPrice cp = new CustomerPrice();
        cp.setCustomerId(customerId);
        cp.setCreatedBy(auditService.currentActorName());
        apply(cp, req, true);
        priceRepository.save(cp);
        auditService.log("CUSTOMER", customerId, "CONTRACT_PRICE", Map.of("sku", cp.getProduct().getSku(), "price", cp.getUnitPrice(),
                "from", cp.getValidFrom().toString(), "to", String.valueOf(cp.getValidTo())));
        return view(cp, tierOf(c), LocalDate.now());
    }

    @Transactional
    public ContractPriceView updateContractPrice(Long customerId, Long id, ContractPriceRequest req) {
        Customer c = customer(customerId);
        CustomerPrice cp = contract(customerId, id);
        Map<String, Object> before = Map.of("price", cp.getUnitPrice(), "from", cp.getValidFrom().toString(), "to", String.valueOf(cp.getValidTo()));
        apply(cp, req, false);
        auditService.log("CUSTOMER", customerId, "CONTRACT_PRICE_CHANGE", Map.of("sku", cp.getProduct().getSku(), "before", before,
                "after", Map.of("price", cp.getUnitPrice(), "from", cp.getValidFrom().toString(), "to", String.valueOf(cp.getValidTo()))));
        return view(cp, tierOf(c), LocalDate.now());
    }

    @Transactional
    public void deleteContractPrice(Long customerId, Long id) {
        CustomerPrice cp = contract(customerId, id);
        priceRepository.delete(cp);
        auditService.log("CUSTOMER", customerId, "CONTRACT_PRICE_DELETE", Map.of("sku", cp.getProduct().getSku(), "price", cp.getUnitPrice()));
    }

    private void apply(CustomerPrice cp, ContractPriceRequest req, boolean isNew) {
        if (isNew) {
            if (req.productId() == null) throw new IllegalArgumentException("Choose the product");
            cp.setProduct(productRepository.findById(req.productId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + req.productId())));
        }
        if (req.unitPrice() == null || req.unitPrice().signum() < 0) throw new IllegalArgumentException("Give the contract price (0 or more)");
        LocalDate from = req.validFrom() != null ? req.validFrom() : LocalDate.now();
        if (req.validTo() != null && req.validTo().isBefore(from)) throw new IllegalArgumentException("The end date is before the start date");
        // One price per SKU at any one time
        for (CustomerPrice other : priceRepository.findByCustomerIdAndProductId(cp.getCustomerId(), cp.getProduct().getId())) {
            if (other.getId() != null && other.getId().equals(cp.getId())) continue;
            boolean overlaps = !from.isAfter(other.getValidTo() == null ? LocalDate.MAX : other.getValidTo())
                    && !(req.validTo() == null ? LocalDate.MAX : req.validTo()).isBefore(other.getValidFrom());
            if (overlaps) {
                throw new IllegalStateException(cp.getProduct().getSku() + " already has a contract price of £" + plain(other.getUnitPrice())
                        + " from " + other.getValidFrom() + (other.getValidTo() == null ? " (no end)" : " to " + other.getValidTo())
                        + ": end that one first");
            }
        }
        cp.setUnitPrice(req.unitPrice());
        cp.setValidFrom(from);
        cp.setValidTo(req.validTo());
        cp.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
    }

    private ContractPriceView view(CustomerPrice cp, PriceTier tier, LocalDate today) {
        Product p = cp.getProduct();
        return new ContractPriceView(cp.getId(), p.getId(), p.getSku(), p.getName(), cp.getUnitPrice(), tierPrice(p, tier),
                cp.getValidFrom(), cp.getValidTo(), cp.getNote(), cp.getCreatedBy(), cp.validOn(today));
    }

    private Customer customer(Long id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found: " + id));
    }

    private CustomerPrice contract(Long customerId, Long id) {
        return priceRepository.findById(id).filter(cp -> cp.getCustomerId().equals(customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Contract price not found: " + id));
    }

    private long customersOn(String tierName) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM customers WHERE tenant_id = ? AND lower(price_tier) = lower(?)",
                Long.class, com.experterp.backend.multitenancy.TenantContext.get(), tierName);
        return n == null ? 0 : n;
    }

    // Organisations made after V25 get the two usual tiers on first use
    private List<PriceTier> ensureTiers() {
        List<PriceTier> tiers = tierRepository.findAllByOrderByNumberAsc();
        if (!tiers.isEmpty()) return tiers;
        PriceTier retail = defaultTier();
        PriceTier trade = new PriceTier();
        trade.setNumber((short) 2);
        trade.setName("Trade");
        tierRepository.save(retail);
        tierRepository.save(trade);
        return tierRepository.findAllByOrderByNumberAsc();
    }

    private static PriceTier defaultTier() {
        PriceTier t = new PriceTier();
        t.setNumber((short) 1);
        t.setName("Retail");
        return t;
    }

    private static String plain(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
