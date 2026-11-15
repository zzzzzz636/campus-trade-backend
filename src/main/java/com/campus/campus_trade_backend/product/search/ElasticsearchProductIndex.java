package com.campus.campus_trade_backend.product.search;

import com.campus.campus_trade_backend.product.entity.ProductEntity;
import com.campus.campus_trade_backend.product.mapper.ProductSearchSnapshotMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "campus.search.enabled", havingValue = "true")
public class ElasticsearchProductIndex {
    private static final String ALIAS = "campus-products";
    private static final String INITIAL_INDEX = "campus-products-000001";
    private final URI base;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper json;
    private final ProductSearchSnapshotMapper snapshots;

    public ElasticsearchProductIndex(@Value("${campus.search.url:http://localhost:9200}") String url,
                                     @Value("${campus.search.api-key:}") String apiKey,
                                     ObjectMapper json, ProductSearchSnapshotMapper snapshots) {
        this.base = URI.create(url.replaceAll("/+$", "") + "/");
        this.apiKey = apiKey;
        this.json = json;
        this.snapshots = snapshots;
    }

    public void indexLatest(long productId) {
        ProductEntity product = snapshots.find(productId);
        if (product == null) throw new IllegalStateException("Product missing for search event: " + productId);
        ensureAlias();
        index(ALIAS, product);
    }

    public SearchPage search(String keyword, Long categoryId, BigDecimal minPrice,
                             BigDecimal maxPrice, int page, int size) {
        ensureAlias();
        if (page < 1 || size < 1 || size > 50 || ((long) page - 1) * size > 10000) {
            throw new IllegalArgumentException("Search pagination out of range");
        }
        List<Object> filters = new ArrayList<>();
        filters.add(Map.of("term", Map.of("status", "ON_SALE")));
        if (categoryId != null) filters.add(Map.of("term", Map.of("category_id", categoryId)));
        if (minPrice != null || maxPrice != null) {
            var range = new java.util.LinkedHashMap<String, BigDecimal>();
            if (minPrice != null) range.put("gte", minPrice);
            if (maxPrice != null) range.put("lte", maxPrice);
            filters.add(Map.of("range", Map.of("price", range)));
        }
        Map<String, Object> query = Map.of("bool", Map.of(
                "must", keyword == null || keyword.isBlank() ? Map.of("match_all", Map.of())
                        : Map.of("multi_match", Map.of("query", keyword, "fields", List.of("title^2", "description"))),
                "filter", filters));
        JsonNode result = request("POST", ALIAS + "/_search", Map.of(
                "query", query, "from", (page - 1) * size, "size", size,
                "track_total_hits", true,
                "sort", List.of(Map.of("_score", "desc"), Map.of("created_at", "desc"), Map.of("id", "desc"))), 200);
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : result.path("hits").path("hits")) ids.add(hit.path("_id").asLong());
        return new SearchPage(ids, result.path("hits").path("total").path("value").asLong());
    }

    // 新索引建好后原子切换别名；水位之后的变更在切换后重放，避免扫描期间的更新丢失。
    public synchronized void rebuild() {
        ensureAlias();
        long watermark = snapshots.eventWatermark();
        String fresh = "campus-products-" + UUID.randomUUID().toString().replace("-", "");
        createIndex(fresh);
        long lastId = 0;
        while (true) {
            List<ProductEntity> batch = snapshots.page(lastId, 200);
            if (batch.isEmpty()) break;
            for (ProductEntity product : batch) index(fresh, product);
            lastId = batch.get(batch.size() - 1).getId();
        }
        JsonNode aliases = request("GET", "_alias/" + ALIAS, null, 200);
        List<Object> actions = new ArrayList<>();
        aliases.properties().forEach(entry -> actions.add(Map.of("remove", Map.of(
                "index", entry.getKey(), "alias", ALIAS))));
        actions.add(Map.of("add", Map.of("index", fresh, "alias", ALIAS)));
        JsonNode response = request("POST", "_aliases", Map.of("actions", actions), 200);
        if (!response.path("acknowledged").asBoolean() || response.path("errors").asBoolean()) {
            throw new IllegalStateException("Elasticsearch alias swap failed");
        }
        for (Long id : snapshots.changedAfter(watermark)) indexLatest(id);
    }

    private synchronized void ensureAlias() {
        if (request("GET", "_alias/" + ALIAS, null, 200, 404) != null) return;
        createIndex(INITIAL_INDEX);
        request("POST", "_aliases", Map.of("actions", List.of(Map.of("add", Map.of(
                "index", INITIAL_INDEX, "alias", ALIAS)))), 200);
    }

    private void createIndex(String name) {
        Map<String, Object> properties = Map.of(
                "id", Map.of("type", "long"), "version", Map.of("type", "long"),
                "title", Map.of("type", "text"), "description", Map.of("type", "text"),
                "status", Map.of("type", "keyword"), "category_id", Map.of("type", "long"),
                "price", Map.of("type", "scaled_float", "scaling_factor", 100),
                "created_at", Map.of("type", "date"));
        JsonNode result = request("PUT", name, Map.of("mappings", Map.of("properties", properties)), 200, 400);
        if (result.has("error") && !"resource_already_exists_exception".equals(
                result.path("error").path("type").asText())) {
            throw new IllegalStateException("Elasticsearch index mapping rejected");
        }
    }

    private void index(String target, ProductEntity product) {
        long version = product.getVersion().longValue() + 1;
        Map<String, Object> document = Map.of(
                "id", product.getId(), "version", product.getVersion(),
                "title", product.getTitle(), "description", product.getDescription(),
                "status", product.getStatus(), "category_id", product.getCategoryId(),
                "price", product.getPrice(), "created_at", product.getCreatedAt().toString());
        request("PUT", target + "/_doc/" + product.getId()
                + "?version=" + version + "&version_type=external_gte", document, 200, 201);
    }

    private JsonNode request(String method, String path, Object body, int... success) {
        try {
            var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json");
            if (!apiKey.isBlank()) builder.header("Authorization", "ApiKey " + apiKey);
            HttpRequest request = builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            for (int status : success) {
                if (status == response.statusCode()) return status == 404 ? null : json.readTree(response.body());
            }
            throw new IllegalStateException("Elasticsearch request failed: HTTP " + response.statusCode());
        } catch (IOException exception) {
            throw new IllegalStateException("Elasticsearch unavailable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Elasticsearch request interrupted", exception);
        }
    }

    public record SearchPage(List<Long> productIds, long total) { }
}
