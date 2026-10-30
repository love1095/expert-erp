package com.experterp.backend.integration.shopify;

import com.experterp.backend.entity.AuditLog.ActorType;
import com.experterp.backend.entity.ShopifyConnection;
import com.experterp.backend.entity.ShopifyProductLink;
import com.experterp.backend.entity.VariantOption;
import com.experterp.backend.entity.Warehouse;
import com.experterp.backend.exception.ResourceNotFoundException;
import com.experterp.backend.multitenancy.TenantContext;
import com.experterp.backend.repository.ShopifyConnectionRepository;
import com.experterp.backend.repository.ShopifyProductLinkRepository;
import com.experterp.backend.repository.StockMovementRepository;
import com.experterp.backend.repository.WarehouseRepository;
import com.experterp.backend.service.AuditActorContext;
import com.experterp.backend.service.AuditService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends the ERP's products to Shopify: title, description, category (product type), brand (vendor), tags, SKU,
 * selling price, cost, weight and the stock of the warehouse that ships Shopify orders.
 * <p>
 * Products of one family (same family SKU, e.g. a polo shirt in several sizes/colours) become ONE Shopify product
 * with a variant per SKU and Size/Colour options; other products become a product with a single variant.
 * Only ACTIVE, sellable stock products are sent.
 * <p>
 * Shopify products are matched by SKU. Ones the ERP created are rebuilt freely: new ones start as DRAFT (the
 * merchant adds photos and publishes them), at £0.00 when the ERP has no selling price yet; variants of products
 * no longer sent are removed, and ERP-made products that nothing uses any more are deleted. Products made by hand
 * in Shopify are only updated (the matching variant's price, SKU, cost; the title only on a single-variant
 * product) and archived, never deleted, when their ERP product is deprecated.
 * Stock is set as Shopify's "on hand" quantity: Shopify keeps its own "committed" count for unshipped orders.
 * <p>
 * Runs in the background (one run per tenant at a time); the page polls {@link #status()}.
 */
@Slf4j
@Service
public class ShopifyProductSyncService {

    static final String LOCATION_QUERY = "{ location { id name } }";

    static final String VARIANTS_QUERY = """
            query($cursor: String, $loc: ID!) {
              productVariants(first: 100, after: $cursor) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id sku
                  product { id hasOnlyDefaultVariant status }
                  inventoryItem { id inventoryLevel(locationId: $loc) { id } }
                }
              }
            }""";

    static final String PRODUCT_SET = """
            mutation($input: ProductSetInput!, $identifier: ProductSetIdentifiers) {
              productSet(synchronous: true, input: $input, identifier: $identifier) {
                product { id variants(first: 250) { nodes { id sku inventoryItem { id } } } }
                userErrors { field message }
              }
            }""";

    static final String PRODUCT_UPDATE = """
            mutation($product: ProductUpdateInput!) {
              productUpdate(product: $product) {
                product { id }
                userErrors { field message }
              }
            }""";

    static final String PRODUCT_DELETE = """
            mutation($input: ProductDeleteInput!) {
              productDelete(input: $input) {
                deletedProductId
                userErrors { field message }
              }
            }""";

    static final String VARIANTS_UPDATE = """
            mutation($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
              productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                productVariants { id }
                userErrors { field message }
              }
            }""";

    static final String SET_QUANTITIES = """
            mutation($input: InventorySetQuantitiesInput!, $idempotencyKey: String!) {
              inventorySetQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { id }
                userErrors { field message }
              }
            }""";

    static final String ACTIVATE = """
            mutation($item: ID!, $loc: ID!, $onHand: Int, $idempotencyKey: String!) {
              inventoryActivate(inventoryItemId: $item, locationId: $loc, onHand: $onHand) @idempotent(key: $idempotencyKey) {
                inventoryLevel { id }
                userErrors { field message }
              }
            }""";

    // Shopify's default (and only) option on a product without variants
    private static final String DEFAULT_OPTION = "Title";
    private static final String DEFAULT_VALUE = "Default Title";
    private static final int STOCK_BATCH = 100;
    private static final int MAX_PROBLEMS = 200;
    // One productSet returns up to 250 variants
    private static final int MAX_VARIANTS = 250;
    private static final int PARALLEL_REQUESTS = 5;
    // Tags that mark a description as HTML (not e.g. "Class 2 <EN ISO 20471>")
    private static final Pattern HTML_TAG = Pattern.compile(
            "(?i)<(!DOCTYPE|/?(html|body|div|p|br|span|ul|ol|li|strong|b|i|em|h[1-6]|table|tr|td|a|img)\\b)");
    private static final Pattern BODY = Pattern.compile("(?is)<body[^>]*>(.*)</body>");
    private static final Pattern NON_CONTENT = Pattern.compile("(?is)<(script|style|head|title)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern PAGE_TAGS = Pattern.compile("(?is)<!DOCTYPE[^>]*>|</?(html|body|meta|link)\\b[^>]*>|<!--.*?-->");

    public enum State { RUNNING, DONE, FAILED }

    /** Steps of a run: send products, remove ones no longer used, set stock of existing variants. */
    public enum Phase { SENDING, REMOVING, STOCK }

    /**
     * Counts are Shopify products (a family is one); variants = SKUs sent; notSent = ERP products that are
     * deprecated, services or not sellable; withoutPrice = variants created at £0.00.
     */
    public record SyncView(State state, Phase phase, int phaseTotal, int phaseDone, int total, int processed, int created, int updated, int unchanged, int removed,
                           int failed, int variants, int notSent, int stockUpdated, int withoutPrice,
                           String warehouseName, String locationName, List<String> problems, String error,
                           LocalDateTime startedAt, LocalDateTime finishedAt) {}

    private final ShopifyConnectionRepository connectionRepository;
    private final ShopifyProductLinkRepository linkRepository;
    private final WarehouseRepository warehouseRepository;
    private final ShopifyConnectionService connectionService;
    private final ShopifyClient client;
    private final AuditService auditService;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper objectMapper;

    // Latest run per tenant (in memory: a restart forgets it, and the next run picks up where Shopify is)
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shopify-product-sync");
        t.setDaemon(true);
        return t;
    });

    public ShopifyProductSyncService(ShopifyConnectionRepository connectionRepository,
                                     ShopifyProductLinkRepository linkRepository, WarehouseRepository warehouseRepository,
                                     ShopifyConnectionService connectionService, ShopifyClient client,
                                     AuditService auditService, NamedParameterJdbcTemplate jdbc, TransactionTemplate tx,
                                     ObjectMapper objectMapper) {
        this.connectionRepository = connectionRepository;
        this.linkRepository = linkRepository;
        this.warehouseRepository = warehouseRepository;
        this.connectionService = connectionService;
        this.client = client;
        this.auditService = auditService;
        this.jdbc = jdbc;
        this.tx = tx;
        this.objectMapper = objectMapper;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** Starts a run for the current tenant, or returns the one already running. */
    public SyncView start() {
        Long tenantId = TenantContext.get();
        ShopifyConnectionService.ConnectionView conn = connectionService.get()
                .orElseThrow(() -> new ResourceNotFoundException("No Shopify store is connected"));
        if (conn.status() != ShopifyConnection.Status.CONNECTED) {
            throw new IllegalArgumentException("The Shopify connection is not working. Click Test connection first.");
        }
        if (!conn.grantedScopes().contains("write_products")) {
            throw new IllegalArgumentException("The Shopify app may not change products yet. In the Dev Dashboard, add the "
                    + "write_products scope to the app, release the new version and approve it on the store, then click Test connection.");
        }

        Job[] started = new Job[1];
        Job job = jobs.compute(tenantId, (k, existing) -> {
            if (existing != null && existing.state == State.RUNNING) return existing;
            started[0] = new Job();
            return started[0];
        });
        if (started[0] != null) {
            tx.executeWithoutResult(s -> connectionRepository.findFirstByOrderByIdAsc().ifPresent(c ->
                    auditService.log("SHOPIFY", c.getId(), "PRODUCT_SYNC_START", Map.of("shop", c.getShopDomain()))));
            executor.submit(() -> run(tenantId, job));
        }
        return job.view();
    }

    /** The current or latest run of the current tenant. */
    public Optional<SyncView> status() {
        return Optional.ofNullable(jobs.get(TenantContext.get())).map(Job::view);
    }

    private void run(Long tenantId, Job job) {
        TenantContext.set(tenantId);
        try {
            AuditActorContext.runAs(ActorType.INTEGRATION, "shopify", () -> {
                sync(job);
                return null;
            });
            job.finish(State.DONE, null);
        } catch (RuntimeException e) {
            log.warn("Product sync to Shopify failed for tenant {}: {}", tenantId, e.getMessage());
            job.finish(State.FAILED, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private void sync(Job job) {
        Target target = tx.execute(s -> {
            ShopifyConnection c = connectionRepository.findFirstByOrderByIdAsc()
                    .orElseThrow(() -> new ResourceNotFoundException("No Shopify store is connected"));
            // Same rule as order import: no warehouse chosen = the oldest one
            Warehouse w = c.getDefaultWarehouse() != null ? c.getDefaultWarehouse()
                    : warehouseRepository.findFirstByOrderByIdAsc().orElseThrow(() -> new IllegalStateException(
                    "No warehouse exists to take stock from. Create one, then choose it on the Shopify page."));
            return new Target(c.getShopDomain(), w.getId(), w.getName());
        });
        job.warehouseName = target.warehouseName();
        String token = connectionService.accessToken();
        String shop = target.shop();

        JsonNode location = client.graphql(shop, token, LOCATION_QUERY, Map.of()).path("location");
        String locationId = location.path("id").asText(null);
        if (locationId == null) throw new ShopifyApiException("Shopify has no location to keep stock at");
        job.locationName = location.path("name").asText(null);

        Map<String, Remote> bySku = new HashMap<>();
        Map<String, Remote> byVariant = new HashMap<>();
        fetchVariants(shop, token, locationId, bySku, byVariant);

        List<ErpProduct> products = loadProducts();
        Map<Long, BigDecimal> stock = loadStock(target.warehouseId());
        Map<Long, ShopifyProductLink> links = new ConcurrentHashMap<>();
        tx.execute(s -> linkRepository.findAll()).forEach(l -> links.put(l.getProductId(), l));

        // ERP-made Shopify products before this run; any not used at the end are deleted
        Set<String> ownedBefore = new HashSet<>();
        links.values().forEach(l -> {
            Remote r = byVariant.get(l.getShopifyVariantId());
            if (r != null && owned(l.getCreatedByErp(), r)) ownedBefore.add(r.productId());
        });

        List<ErpProduct> eligible = new ArrayList<>();
        List<ErpProduct> notSent = new ArrayList<>();
        for (ErpProduct p : products) (p.sendable() ? eligible : notSent).add(p);
        job.notSent.set(notSent.size());

        Ctx ctx = new Ctx(shop, token, locationId, bySku, byVariant, links, stock,
                Collections.synchronizedList(new ArrayList<>()), Collections.synchronizedList(new ArrayList<>()),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
        List<Group> groups = group(eligible, job);
        job.total.set(groups.size());

        job.startPhase(Phase.SENDING, groups.size());
        inParallel(groups, g -> {
            try {
                syncGroup(ctx, job, g);
            } catch (RuntimeException e) {
                job.failed.incrementAndGet();
                job.problem(g.label() + ": " + e.getMessage());
            } finally {
                job.processed.incrementAndGet();
                job.phaseDone.incrementAndGet();
            }
        });

        removeUnused(ctx, job, notSent, ownedBefore);
        pushStock(ctx, job);

        if (!ctx.noPrice().isEmpty()) {
            List<String> examples = new ArrayList<>(ctx.noPrice());
            Collections.sort(examples);
            job.problems.add(0, examples.size() + " variant(s) created at £0.00 because they have no selling price:"
                    + " set a price in the ERP and sync again before making them Active in Shopify (e.g. "
                    + String.join(", ", examples.subList(0, Math.min(5, examples.size()))) + ")");
        }
        tx.executeWithoutResult(s -> connectionRepository.findFirstByOrderByIdAsc().ifPresent(c -> {
            c.setLastProductSyncAt(LocalDateTime.now());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("products", job.total.get());
            summary.put("created", job.created.get());
            summary.put("updated", job.updated.get());
            summary.put("unchanged", job.unchanged.get());
            summary.put("removed", job.removed.get());
            summary.put("failed", job.failed.get());
            summary.put("variants", job.variants.get());
            summary.put("notSent", job.notSent.get());
            summary.put("withoutPrice", job.withoutPrice.get());
            auditService.log("SHOPIFY", c.getId(), "PRODUCT_SYNC", summary);
        }));
    }

    /**
     * Runs work for each item, several at a time. One Shopify call takes about a second; a few at once stay well
     * inside Shopify's rate limit (ShopifyClient waits and retries when Shopify asks to slow down).
     */
    private <T> void inParallel(List<T> items, java.util.function.Consumer<T> work) {
        Long tenantId = TenantContext.get();
        ExecutorService workers = Executors.newFixedThreadPool(PARALLEL_REQUESTS);
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (T item : items) {
                pending.add(workers.submit(() -> {
                    TenantContext.set(tenantId);
                    try {
                        AuditActorContext.runAs(ActorType.INTEGRATION, "shopify", () -> {
                            work.accept(item);
                            return null;
                        });
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            for (Future<?> f : pending) f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Product sync was interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        } finally {
            workers.shutdownNow();
        }
    }

    // ---- grouping ----

    /** A family's products become one Shopify product; an invalid family is sent as separate products. */
    private List<Group> group(List<ErpProduct> eligible, Job job) {
        Map<String, List<ErpProduct>> families = new LinkedHashMap<>();
        List<Group> groups = new ArrayList<>();
        for (ErpProduct p : eligible) {
            if (p.familySku() != null && !p.options().isEmpty()) {
                families.computeIfAbsent(p.familySku(), k -> new ArrayList<>()).add(p);
            } else {
                groups.add(Group.single(p));
            }
        }
        families.forEach((familySku, members) -> {
            String problem = familyProblem(members);
            if (problem == null) {
                members.sort(Comparator.comparing(ErpProduct::sku));
                groups.add(Group.family(familySku, members));
            } else {
                job.problem("Family " + familySku + ": " + problem + " — sent as separate products");
                members.forEach(m -> groups.add(Group.single(m)));
            }
        });
        return groups;
    }

    static String familyProblem(List<ErpProduct> members) {
        List<String> names = members.get(0).options().stream().map(VariantOption::name).toList();
        if (names.size() > 3) return "more than 3 options";
        if (members.size() > MAX_VARIANTS) return "more than " + MAX_VARIANTS + " products";
        Set<List<String>> combos = new HashSet<>();
        for (ErpProduct m : members) {
            List<String> mine = m.options().stream().map(VariantOption::name).toList();
            if (!mine.equals(names)) return m.sku() + " has options " + mine + " instead of " + names;
            if (!combos.add(m.options().stream().map(VariantOption::value).toList())) {
                return "two products have the same " + String.join("/", names) + " (" + m.sku() + ")";
            }
        }
        return null;
    }

    // ---- one Shopify product ----

    private void syncGroup(Ctx ctx, Job job, Group g) {
        // Resolve each member's current Shopify variant; split off ones that belong to hand-made Shopify products
        List<ErpProduct> mine = new ArrayList<>();
        for (ErpProduct p : g.members()) {
            ShopifyProductLink link = ctx.links().get(p.id());
            Remote remote = link == null ? null : ctx.byVariant().get(link.getShopifyVariantId());
            if (link != null && remote == null) {
                // Deleted in Shopify: match again by SKU, or create it again
                ShopifyProductLink gone = link;
                tx.executeWithoutResult(s -> linkRepository.deleteById(gone.getId()));
                ctx.links().remove(p.id());
                link = null;
            }
            if (link == null && (remote = ctx.bySku().get(p.sku())) != null) {
                link = newLink(p.id(), remote, remote.isDraft() ? null : Boolean.FALSE);
                ctx.links().put(p.id(), link);
            }
            if (link != null && !owned(link.getCreatedByErp(), remote)) {
                updateHandMade(ctx, job, p, link, remote, g.isFamily());
            } else {
                mine.add(p);
            }
        }
        if (mine.isEmpty()) return;

        // The ERP-made Shopify product to update, if any: same shape (family = several variants, single = one)
        Map<String, Remote> current = new LinkedHashMap<>();
        for (ErpProduct p : mine) {
            ShopifyProductLink l = ctx.links().get(p.id());
            if (l != null) current.put(l.getShopifyProductId(), ctx.byVariant().get(l.getShopifyVariantId()));
        }
        String target = current.values().stream()
                .filter(r -> r.singleVariant() != g.isFamily())
                .map(Remote::productId)
                .max(Comparator.comparingLong(id -> mine.stream().filter(p -> {
                    ShopifyProductLink l = ctx.links().get(p.id());
                    return l != null && l.getShopifyProductId().equals(id);
                }).count()))
                .orElse(null);
        // Other single-variant ERP-made products of these SKUs (e.g. made before families were sent) are replaced
        for (Remote r : current.values()) {
            if (!r.productId().equals(target) && r.singleVariant()) {
                deleteProduct(ctx, job, r.productId());
            }
        }

        // Members already in the target product (their stock is set separately) vs new variants
        Set<Long> existing = new HashSet<>();
        for (ErpProduct p : mine) {
            ShopifyProductLink l = ctx.links().get(p.id());
            if (target != null && l != null && l.getShopifyProductId().equals(target)) existing.add(p.id());
        }

        String hash = hash(g, mine);
        boolean unchanged = target != null && existing.size() == mine.size()
                && mine.stream().allMatch(p -> hash.equals(ctx.links().get(p.id()).getContentHash()));
        String productId = target;
        if (unchanged) {
            job.unchanged.incrementAndGet();
        } else {
            productId = productSet(ctx, job, g, mine, target, existing, hash);
            (target == null ? job.created : job.updated).incrementAndGet();
        }
        ctx.kept().add(productId);
        job.variants.addAndGet(mine.size());
        for (ErpProduct p : mine) {
            if (existing.contains(p.id())) {
                ctx.stockUpdates().add(new StockSet(p.sku(), ctx.links().get(p.id()).getShopifyInventoryItemId(),
                        shopifyQuantity(ctx.stock().get(p.id()))));
            }
        }
    }

    /** Creates or rebuilds the ERP-made Shopify product of a group; returns its id. */
    private String productSet(Ctx ctx, Job job, Group g, List<ErpProduct> members, String target, Set<Long> existing,
                              String hash) {
        ErpProduct first = members.get(0);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("title", g.isFamily() && first.familyName() != null ? first.familyName() : first.name());
        input.put("descriptionHtml", descriptionHtml(members.stream().map(ErpProduct::description)
                .filter(d -> d != null && !d.isBlank()).findFirst().orElse(null)));
        input.put("productType", first.category() == null ? "" : first.category());
        input.put("vendor", first.brand() == null ? "" : first.brand());
        Set<String> tags = new LinkedHashSet<>();
        for (ErpProduct m : members) {
            if (m.tags() != null) Arrays.stream(m.tags().split(",")).map(String::trim).filter(t -> !t.isEmpty()).forEach(tags::add);
        }
        input.put("tags", new ArrayList<>(tags));
        // Draft: the merchant adds photos and publishes it in Shopify; an existing product keeps its status
        if (target == null) input.put("status", "DRAFT");

        List<String> optionNames = g.isFamily() ? first.options().stream().map(VariantOption::name).toList() : List.of(DEFAULT_OPTION);
        List<Map<String, Object>> productOptions = new ArrayList<>();
        for (int i = 0; i < optionNames.size(); i++) {
            int index = i;
            List<Map<String, Object>> values = members.stream()
                    .map(m -> g.isFamily() ? m.options().get(index).value() : DEFAULT_VALUE)
                    .distinct().map(v -> Map.<String, Object>of("name", v)).toList();
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("name", optionNames.get(i));
            option.put("values", values);
            productOptions.add(option);
        }
        input.put("productOptions", productOptions);

        List<Map<String, Object>> variants = new ArrayList<>();
        for (ErpProduct m : members) {
            Map<String, Object> v = new LinkedHashMap<>();
            if (existing.contains(m.id())) v.put("id", ctx.links().get(m.id()).getShopifyVariantId());
            List<Map<String, Object>> optionValues = new ArrayList<>();
            for (int i = 0; i < optionNames.size(); i++) {
                optionValues.add(Map.of("optionName", optionNames.get(i),
                        "name", g.isFamily() ? m.options().get(i).value() : DEFAULT_VALUE));
            }
            v.put("optionValues", optionValues);
            v.put("sku", m.sku());
            // No selling price yet: 0.00 on a draft, so nothing is sold for free until the merchant prices and publishes it
            v.put("price", m.sellPrice() == null ? "0.00" : m.sellPrice().toPlainString());
            v.put("inventoryPolicy", "DENY");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tracked", true);
            if (m.costPrice() != null) item.put("cost", m.costPrice().toPlainString());
            String weightUnit = shopifyWeightUnit(m.weightUnit());
            if (m.weight() != null && weightUnit != null) {
                item.put("measurement", Map.of("weight", Map.of("value", m.weight(), "unit", weightUnit)));
            }
            v.put("inventoryItem", item);
            if (!existing.contains(m.id())) {
                // New variant: its stock is set as it is created
                v.put("inventoryQuantities", List.of(Map.of("locationId", ctx.locationId(), "name", "available",
                        "quantity", shopifyQuantity(ctx.stock().get(m.id())))));
                job.stockUpdated.incrementAndGet();
                if (m.sellPrice() == null) {
                    job.withoutPrice.incrementAndGet();
                    ctx.noPrice().add(m.sku());
                }
            }
            variants.add(v);
        }
        input.put("variants", variants);

        Map<String, Object> vars = new HashMap<>();
        vars.put("input", input);
        vars.put("identifier", target == null ? null : Map.of("id", target));
        JsonNode result = client.graphql(ctx.shop(), ctx.token(), PRODUCT_SET, vars).path("productSet");
        userErrors(result, "Shopify refused the product");
        JsonNode product = result.path("product");
        String productId = product.path("id").asText(null);
        if (productId == null) throw new ShopifyApiException("Shopify did not return the product");

        Map<String, JsonNode> returned = new HashMap<>();
        for (JsonNode v : product.path("variants").path("nodes")) returned.put(v.path("sku").asText(), v);
        List<ShopifyProductLink> toSave = new ArrayList<>();
        for (ErpProduct m : members) {
            JsonNode v = returned.get(m.sku());
            if (v == null) {
                job.problem(m.sku() + ": Shopify did not return its variant");
                continue;
            }
            ShopifyProductLink link = ctx.links().get(m.id());
            if (link == null) {
                link = new ShopifyProductLink();
                link.setProductId(m.id());
            }
            link.setShopifyProductId(productId);
            link.setShopifyVariantId(v.path("id").asText());
            link.setShopifyInventoryItemId(v.path("inventoryItem").path("id").asText());
            link.setSingleVariant(!g.isFamily());
            link.setCreatedByErp(true);
            link.setContentHash(hash);
            link.setSyncedAt(LocalDateTime.now());
            ctx.links().put(m.id(), link);
            toSave.add(link);
        }
        tx.executeWithoutResult(s -> toSave.forEach(linkRepository::save));
        return productId;
    }

    /** A product made by hand in Shopify: update the matching variant (and the title of a single-variant product). */
    private void updateHandMade(Ctx ctx, Job job, ErpProduct p, ShopifyProductLink link, Remote remote, boolean inFamily) {
        link.setSingleVariant(remote.singleVariant());
        link.setCreatedByErp(false);
        String hash = sha256(hash(Group.single(p), List.of(p)) + remote.singleVariant());
        if (!hash.equals(link.getContentHash())) {
            if (remote.singleVariant() && !inFamily) {
                Map<String, Object> product = new LinkedHashMap<>();
                product.put("id", link.getShopifyProductId());
                product.put("title", p.name());
                product.put("descriptionHtml", descriptionHtml(p.description()));
                product.put("productType", p.category() == null ? "" : p.category());
                product.put("vendor", p.brand() == null ? "" : p.brand());
                userErrors(client.graphql(ctx.shop(), ctx.token(), PRODUCT_UPDATE, Map.of("product", product))
                        .path("productUpdate"), "Shopify refused the product");
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sku", p.sku());
            item.put("tracked", true);
            if (p.costPrice() != null) item.put("cost", p.costPrice().toPlainString());
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("id", link.getShopifyVariantId());
            if (p.sellPrice() != null) variant.put("price", p.sellPrice().toPlainString());
            variant.put("inventoryItem", item);
            userErrors(client.graphql(ctx.shop(), ctx.token(), VARIANTS_UPDATE,
                    Map.of("productId", link.getShopifyProductId(), "variants", List.of(variant)))
                    .path("productVariantsBulkUpdate"), "Shopify refused the price/SKU");
            link.setContentHash(hash);
            job.updated.incrementAndGet();
        } else {
            job.unchanged.incrementAndGet();
        }
        link.setSyncedAt(LocalDateTime.now());
        tx.executeWithoutResult(s -> linkRepository.save(link));
        job.variants.incrementAndGet();

        int quantity = shopifyQuantity(ctx.stock().get(p.id()));
        if (remote.stocked()) {
            ctx.stockUpdates().add(new StockSet(p.sku(), remote.inventoryItemId(), quantity));
        } else {
            // Not stocked at this location yet: start stocking it there
            userErrors(client.graphql(ctx.shop(), ctx.token(), ACTIVATE, Map.of("item", remote.inventoryItemId(),
                    "loc", ctx.locationId(), "onHand", quantity, "idempotencyKey", UUID.randomUUID().toString()))
                    .path("inventoryActivate"), "Shopify refused to stock it at the location");
            job.stockUpdated.incrementAndGet();
        }
    }

    /**
     * After all products were sent: ERP-made Shopify products nothing uses any more are deleted; hand-made ones whose
     * ERP product is no longer sent are archived (single-variant) or just unlinked.
     */
    private void removeUnused(Ctx ctx, Job job, List<ErpProduct> notSent, Set<String> ownedBefore) {
        List<String> unused = ownedBefore.stream()
                .filter(id -> !ctx.kept().contains(id) && !ctx.deleted().contains(id)).toList();
        List<ErpProduct> linkedNotSent = notSent.stream().filter(p -> ctx.links().containsKey(p.id())).toList();
        job.startPhase(Phase.REMOVING, unused.size() + linkedNotSent.size());
        inParallel(unused, productId -> {
            try {
                deleteProduct(ctx, job, productId);
            } catch (RuntimeException e) {
                job.failed.incrementAndGet();
                job.problem(productId + ": not deleted — " + e.getMessage());
            } finally {
                job.phaseDone.incrementAndGet();
            }
        });
        for (ErpProduct p : linkedNotSent) {
            job.phaseDone.incrementAndGet();
            ShopifyProductLink link = ctx.links().get(p.id());
            if (link == null) continue;
            Remote remote = ctx.byVariant().get(link.getShopifyVariantId());
            try {
                if (remote != null && !owned(link.getCreatedByErp(), remote) && remote.singleVariant()
                        && !ctx.kept().contains(remote.productId())) {
                    userErrors(client.graphql(ctx.shop(), ctx.token(), PRODUCT_UPDATE,
                            Map.of("product", Map.of("id", remote.productId(), "status", "ARCHIVED")))
                            .path("productUpdate"), "Shopify refused to archive it");
                    job.removed.incrementAndGet();
                }
                tx.executeWithoutResult(s -> linkRepository.deleteById(link.getId()));
                ctx.links().remove(p.id());
            } catch (RuntimeException e) {
                job.failed.incrementAndGet();
                job.problem(p.sku() + ": not archived — " + e.getMessage());
            }
        }
    }

    private void deleteProduct(Ctx ctx, Job job, String productId) {
        userErrors(client.graphql(ctx.shop(), ctx.token(), PRODUCT_DELETE, Map.of("input", Map.of("id", productId)))
                .path("productDelete"), "Shopify refused to delete the product");
        tx.executeWithoutResult(s -> linkRepository.deleteAll(linkRepository.findByShopifyProductId(productId)));
        ctx.links().values().removeIf(l -> productId.equals(l.getShopifyProductId()));
        ctx.deleted().add(productId);
        job.removed.incrementAndGet();
    }

    /** Sets on-hand quantities in batches; a refused batch is retried item by item so one bad item names itself. */
    private void pushStock(Ctx ctx, Job job) {
        List<StockSet> all = new ArrayList<>(ctx.stockUpdates());
        job.startPhase(Phase.STOCK, all.size());
        for (int i = 0; i < all.size(); i += STOCK_BATCH) {
            List<StockSet> batch = all.subList(i, Math.min(i + STOCK_BATCH, all.size()));
            job.phaseDone.addAndGet(batch.size());
            try {
                setQuantities(ctx, batch);
                job.stockUpdated.addAndGet(batch.size());
            } catch (ShopifyApiException e) {
                for (StockSet one : batch) {
                    try {
                        setQuantities(ctx, List.of(one));
                        job.stockUpdated.incrementAndGet();
                    } catch (ShopifyApiException single) {
                        job.failed.incrementAndGet();
                        job.problem(one.sku() + ": stock not updated — " + single.getMessage());
                    }
                }
            }
        }
    }

    private void setQuantities(Ctx ctx, List<StockSet> batch) {
        List<Map<String, Object>> quantities = new ArrayList<>();
        for (StockSet s : batch) {
            Map<String, Object> q = new HashMap<>();
            q.put("inventoryItemId", s.inventoryItemId());
            q.put("locationId", ctx.locationId());
            q.put("quantity", s.quantity());
            // Required since 2026-04; null = set it whatever Shopify has now (the ERP is the source of truth)
            q.put("changeFromQuantity", null);
            quantities.add(q);
        }
        Map<String, Object> input = Map.of("name", "on_hand", "reason", "correction", "quantities", quantities);
        userErrors(client.graphql(ctx.shop(), ctx.token(), SET_QUANTITIES,
                Map.of("input", input, "idempotencyKey", UUID.randomUUID().toString()))
                .path("inventorySetQuantities"), "Shopify refused the stock update");
    }

    private void fetchVariants(String shop, String token, String locationId,
                               Map<String, Remote> bySku, Map<String, Remote> byVariant) {
        String cursor = null;
        do {
            Map<String, Object> vars = new HashMap<>();
            vars.put("cursor", cursor);
            vars.put("loc", locationId);
            JsonNode page = client.graphql(shop, token, VARIANTS_QUERY, vars).path("productVariants");
            for (JsonNode v : page.path("nodes")) {
                JsonNode item = v.path("inventoryItem");
                JsonNode product = v.path("product");
                Remote r = new Remote(product.path("id").asText(), v.path("id").asText(), item.path("id").asText(),
                        product.path("hasOnlyDefaultVariant").asBoolean(false),
                        !item.path("inventoryLevel").isMissingNode() && !item.path("inventoryLevel").isNull(),
                        product.path("status").asText(""));
                byVariant.put(r.variantId(), r);
                String sku = v.path("sku").asText("").trim();
                // Two Shopify variants with one SKU: the first one found wins
                if (!sku.isEmpty()) bySku.putIfAbsent(sku, r);
            }
            cursor = page.path("pageInfo").path("hasNextPage").asBoolean(false)
                    ? page.path("pageInfo").path("endCursor").asText() : null;
        } while (cursor != null);
    }

    // Native queries: tenant filter by hand
    private List<ErpProduct> loadProducts() {
        TypeReference<List<VariantOption>> optionsType = new TypeReference<>() {};
        return jdbc.query("""
                        SELECT p.id, p.sku, p.name, p.description, p.sell_price, p.cost_price, c.name AS category,
                               p.brand, p.status, p.product_type, p.sellable, p.family_sku, p.family_name,
                               p.variant_options::text AS options, p.details->>'tags' AS tags,
                               p.details->>'weight' AS weight, p.details->>'weightUnit' AS weight_unit
                        FROM products p LEFT JOIN categories c ON c.id = p.category_id
                        WHERE p.tenant_id = :t ORDER BY p.id""",
                Map.of("t", TenantContext.get()),
                (rs, i) -> {
                    List<VariantOption> options = List.of();
                    String json = rs.getString("options");
                    if (json != null) {
                        try {
                            options = objectMapper.readValue(json, optionsType);
                        } catch (IOException e) {
                            options = List.of();
                        }
                    }
                    String weight = rs.getString("weight");
                    boolean sendable = "ACTIVE".equals(rs.getString("status")) && "STOCK".equals(rs.getString("product_type"))
                            && rs.getBoolean("sellable");
                    return new ErpProduct(rs.getLong("id"), rs.getString("sku"), rs.getString("name"),
                            rs.getString("description"), rs.getBigDecimal("sell_price"), rs.getBigDecimal("cost_price"),
                            rs.getString("category"), rs.getString("brand"), rs.getString("tags"),
                            rs.getString("family_sku"), rs.getString("family_name"), options,
                            weight == null ? null : new BigDecimal(weight), rs.getString("weight_unit"), sendable);
                });
    }

    private Map<Long, BigDecimal> loadStock(Long warehouseId) {
        Map<Long, BigDecimal> stock = new HashMap<>();
        jdbc.query("SELECT product_id, COALESCE(SUM(CASE WHEN type IN (" + StockMovementRepository.IN_TYPES + ") "
                        + "THEN quantity ELSE -quantity END), 0) AS stock "
                        + "FROM stock_movements WHERE tenant_id = :t AND warehouse_id = :w GROUP BY product_id",
                Map.of("t", TenantContext.get(), "w", warehouseId),
                (RowCallbackHandler) rs -> stock.put(rs.getLong("product_id"), rs.getBigDecimal("stock")));
        return stock;
    }

    // ---- small rules ----

    /** ERP-made: recorded as such, or (linked before that was recorded) still a draft in Shopify. */
    static boolean owned(Boolean createdByErp, Remote remote) {
        if (createdByErp != null) return createdByErp;
        return remote != null && remote.isDraft();
    }

    private static ShopifyProductLink newLink(Long productId, Remote remote, Boolean createdByErp) {
        ShopifyProductLink link = new ShopifyProductLink();
        link.setProductId(productId);
        link.setShopifyProductId(remote.productId());
        link.setShopifyVariantId(remote.variantId());
        link.setShopifyInventoryItemId(remote.inventoryItemId());
        link.setSingleVariant(remote.singleVariant());
        link.setCreatedByErp(createdByErp);
        return link;
    }

    /** Whole units, never negative (Shopify would oversell otherwise). */
    static int shopifyQuantity(BigDecimal stock) {
        if (stock == null || stock.signum() <= 0) return 0;
        return stock.setScale(0, RoundingMode.FLOOR).intValue();
    }

    static String shopifyWeightUnit(String unit) {
        if (unit == null) return null;
        return switch (unit.trim().toLowerCase(Locale.ROOT)) {
            case "g", "gram", "grams" -> "GRAMS";
            case "kg", "kilogram", "kilograms" -> "KILOGRAMS";
            case "lb", "lbs", "pound", "pounds" -> "POUNDS";
            case "oz", "ounce", "ounces" -> "OUNCES";
            default -> null;
        };
    }

    /**
     * Description as Shopify HTML. Cin7 descriptions are HTML, sometimes a whole web page: only the body is kept,
     * without scripts, styles and page tags. Plain text is escaped, line breaks kept.
     */
    static String descriptionHtml(String text) {
        if (text == null || text.isBlank()) return "";
        if (HTML_TAG.matcher(text).find()) {
            String html = text;
            Matcher body = BODY.matcher(html);
            if (body.find()) html = body.group(1);
            html = NON_CONTENT.matcher(html).replaceAll("");
            html = PAGE_TAGS.matcher(html).replaceAll("");
            return html.strip();
        }
        String escaped = text.strip().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        StringBuilder html = new StringBuilder();
        for (String para : escaped.split("\\R\\s*\\R")) {
            html.append("<p>").append(para.strip().replaceAll("\\R", "<br>")).append("</p>");
        }
        return html.toString();
    }

    private static String hash(Group g, List<ErpProduct> members) {
        StringBuilder s = new StringBuilder(g.isFamily() ? "F" : "S");
        for (ErpProduct p : members) {
            s.append('\u0002').append(String.join("\u0001", p.sku(), p.name(), Objects.toString(p.description(), ""),
                    Objects.toString(p.category(), ""), Objects.toString(p.brand(), ""), Objects.toString(p.tags(), ""),
                    Objects.toString(p.familyName(), ""), String.valueOf(p.options()), plain(p.sellPrice()),
                    plain(p.costPrice()), plain(p.weight()), Objects.toString(p.weightUnit(), "")));
        }
        return sha256(s.toString());
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String plain(BigDecimal d) {
        return d == null ? "" : d.stripTrailingZeros().toPlainString();
    }

    private static void userErrors(JsonNode result, String what) {
        JsonNode errors = result.path("userErrors");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(e.path("message").asText()));
            throw new ShopifyApiException(what + ": " + String.join("; ", messages));
        }
    }

    // ---- state ----

    private record Target(String shop, Long warehouseId, String warehouseName) {}

    record ErpProduct(Long id, String sku, String name, String description, BigDecimal sellPrice, BigDecimal costPrice,
                      String category, String brand, String tags, String familySku, String familyName,
                      List<VariantOption> options, BigDecimal weight, String weightUnit, boolean sendable) {}

    /** One Shopify product: a family (several variants with options) or a single product. */
    private record Group(String familySku, List<ErpProduct> members) {
        static Group single(ErpProduct p) {
            return new Group(null, List.of(p));
        }

        static Group family(String familySku, List<ErpProduct> members) {
            return new Group(familySku, members);
        }

        boolean isFamily() {
            return familySku != null;
        }

        String label() {
            return isFamily() ? "Family " + familySku : members.get(0).sku();
        }
    }

    /** A Shopify variant; stocked = it has an inventory level at the sync location. */
    record Remote(String productId, String variantId, String inventoryItemId, boolean singleVariant, boolean stocked,
                  String status) {
        boolean isDraft() {
            return "DRAFT".equals(status);
        }
    }

    private record StockSet(String sku, String inventoryItemId, int quantity) {}

    private record Ctx(String shop, String token, String locationId, Map<String, Remote> bySku,
                       Map<String, Remote> byVariant, Map<Long, ShopifyProductLink> links, Map<Long, BigDecimal> stock,
                       List<StockSet> stockUpdates, List<String> noPrice, Set<String> kept, Set<String> deleted) {}

    /** Progress of one run; written by the sync threads, read by status requests. */
    private static final class Job {
        volatile State state = State.RUNNING;
        final AtomicInteger total = new AtomicInteger(), processed = new AtomicInteger(), created = new AtomicInteger(),
                updated = new AtomicInteger(), unchanged = new AtomicInteger(), removed = new AtomicInteger(),
                failed = new AtomicInteger(), variants = new AtomicInteger(), notSent = new AtomicInteger(),
                stockUpdated = new AtomicInteger(), withoutPrice = new AtomicInteger();
        volatile String warehouseName, locationName, error;
        volatile Phase phase = Phase.SENDING;
        final AtomicInteger phaseTotal = new AtomicInteger(), phaseDone = new AtomicInteger();
        final List<String> problems = Collections.synchronizedList(new ArrayList<>());
        final LocalDateTime startedAt = LocalDateTime.now();
        volatile LocalDateTime finishedAt;

        void startPhase(Phase phase, int total) {
            phaseDone.set(0);
            phaseTotal.set(total);
            this.phase = phase;
        }

        void problem(String message) {
            if (problems.size() < MAX_PROBLEMS) problems.add(message);
        }

        void finish(State state, String error) {
            this.error = error;
            this.finishedAt = LocalDateTime.now();
            this.state = state;
        }

        SyncView view() {
            List<String> copy;
            synchronized (problems) {
                copy = List.copyOf(problems);
            }
            return new SyncView(state, phase, phaseTotal.get(), phaseDone.get(), total.get(), processed.get(), created.get(), updated.get(), unchanged.get(),
                    removed.get(), failed.get(), variants.get(), notSent.get(), stockUpdated.get(), withoutPrice.get(),
                    warehouseName, locationName, copy, error, startedAt, finishedAt);
        }
    }
}
