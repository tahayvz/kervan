package com.kervan.search.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kervan.search.domain.model.SearchQuery;
import com.kervan.search.domain.model.SearchResult;
import com.kervan.search.domain.model.SearchableProduct;
import com.kervan.search.domain.port.ProductIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.elasticsearch.VersionConflictException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchTemplate;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ProductIndex} portunun Elasticsearch uygulaması.
 */
@Component
class ElasticsearchProductIndex implements ProductIndex {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchProductIndex.class);

    private static final String BRAND_FACET = "brands";
    private static final String CATEGORY_FACET = "categories";

    /** Facet listesinin üst sınırı; binlerce markayı tek cevapta döndürmek istenmez. */
    private static final int FACET_LIMIT = 20;

    private final ElasticsearchTemplate template;

    ElasticsearchProductIndex(ElasticsearchTemplate template) {
        this.template = template;
    }

    @Override
    public boolean index(SearchableProduct product) {
        try {
            template.save(ProductIndexDocument.from(product));
            return true;
        } catch (VersionConflictException e) {
            // Dış sürüm daha küçük ya da eşit: bu olay ya tekrar geldi ya da geç
            // kaldı. Yazsaydık arama geçmişe dönerdi. Hata değil, beklenen durum —
            // idempotentlik ve sıra koruması bu tek istisnadan geliyor.
            log.debug("Daha yeni bir kayıt var, indeksleme atlandı: id={} sürüm={}",
                    product.id(), product.version());
            return false;
        }
    }

    @Override
    public void delete(String productId) {
        template.delete(productId, ProductIndexDocument.class);
    }

    @Override
    public SearchResult search(SearchQuery query) {
        NativeQueryBuilder builder = NativeQuery.builder()
                .withQuery(buildQuery(query))
                .withPageable(PageRequest.of(query.page(), query.size()));

        // Facet'ler aynı sorguda hesaplanır. Ayrı sorgu olsaydı hem iki tur olurdu
        // hem de süzgeçlerle tutarsız kalabilirdi.
        builder.withAggregation(BRAND_FACET, termsAggregation("brand"));
        builder.withAggregation(CATEGORY_FACET, termsAggregation("categoryPath"));

        SearchHits<ProductIndexDocument> hits =
                template.search(builder.build(), ProductIndexDocument.class);

        return new SearchResult(
                hits.getSearchHits().stream()
                        .map(hit -> hit.getContent().toDomain())
                        .toList(),
                hits.getTotalHits(),
                FacetReader.read(hits, BRAND_FACET),
                FacetReader.read(hits, CATEGORY_FACET));
    }

    private static co.elastic.clients.elasticsearch._types.aggregations.Aggregation
            termsAggregation(String field) {
        return co.elastic.clients.elasticsearch._types.aggregations.Aggregation.of(
                a -> a.terms(t -> t.field(field).size(FACET_LIMIT)));
    }

    /**
     * Süzgeçleri tek bir boolean sorguda birleştirir.
     *
     * <p>Metin araması {@code must} altında: skorlamaya katkı verir, sonuçlar
     * benzerliğe göre sıralanır. Süzgeçler ise {@code filter} altında: skorlamaya
     * katılmazlar, yalnızca eler. Bu ayrım hem doğru sıralama hem de Elasticsearch'ün
     * süzgeç önbelleğini kullanabilmesi için gerekli.
     */
    private static Query buildQuery(SearchQuery query) {
        List<Query> must = new ArrayList<>();
        List<Query> filters = new ArrayList<>();

        if (query.hasText()) {
            must.add(Query.of(q -> q.multiMatch(m -> m
                    .query(query.text())
                    .fields("name^3", "description", "brand^2"))));
        }

        if (!query.brands().isEmpty()) {
            filters.add(Query.of(q -> q.terms(t -> t
                    .field("brand")
                    .terms(v -> v.value(query.brands().stream()
                            .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                            .toList())))));
        }

        if (query.categoryPath() != null && !query.categoryPath().isBlank()) {
            // Ön ek eşleşmesi: "elektronik" araması "elektronik/telefon" ürünlerini de
            // getirir. Kategori bir ağaç ve kullanıcı üst dalı seçebilmeli.
            filters.add(Query.of(q -> q.prefix(p -> p
                    .field("categoryPath").value(query.categoryPath()))));
        }

        if (query.minPrice() != null || query.maxPrice() != null) {
            filters.add(priceRange(query.minPrice(), query.maxPrice()));
        }

        if (must.isEmpty() && filters.isEmpty()) {
            return Query.of(q -> q.matchAll(m -> m));
        }
        return Query.of(q -> q.bool(b -> b.must(must).filter(filters)));
    }

    private static Query priceRange(BigDecimal min, BigDecimal max) {
        return Query.of(q -> q.range(r -> r.number(n -> {
            n.field("price");
            if (min != null) {
                n.gte(min.doubleValue());
            }
            if (max != null) {
                n.lte(max.doubleValue());
            }
            return n;
        })));
    }

    /** Elasticsearch'ün agregasyon cevabını sayaç tablosuna çevirir. */
    private static final class FacetReader {

        private FacetReader() {
        }

        static Map<String, Long> read(SearchHits<ProductIndexDocument> hits, String name) {
            var aggregations = hits.getAggregations();
            if (aggregations == null) {
                return Map.of();
            }

            var aggregation = ((org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations)
                    aggregations).get(name);
            if (aggregation == null) {
                return Map.of();
            }

            Map<String, Long> counts = new LinkedHashMap<>();
            aggregation.aggregation().getAggregate().sterms().buckets().array()
                    .forEach(bucket -> counts.put(bucket.key().stringValue(), bucket.docCount()));
            return counts;
        }
    }
}
