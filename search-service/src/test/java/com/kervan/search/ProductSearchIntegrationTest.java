package com.kervan.search;

import com.kervan.search.domain.model.SearchQuery;
import com.kervan.search.domain.model.SearchResult;
import com.kervan.search.domain.model.SearchableProduct;
import com.kervan.search.domain.port.ProductIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.kervan.search.infrastructure.elasticsearch.ProductIndexInitializer;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Arama, süzgeçler, facet'ler ve sürüm koruması — gerçek Elasticsearch'e karşı.
 */
@DisplayName("Ürün araması")
class ProductSearchIntegrationTest extends AbstractSearchIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    @Autowired
    private ProductIndex index;

    @Autowired
    private ElasticsearchOperations elasticsearch;

    @Autowired
    private ProductIndexInitializer indexInitializer;

    @BeforeEach
    void resetIndex() {
        elasticsearch.indexOps(IndexCoordinates.of("products")).delete();
        // Yeniden kurarken de üretimdeki yol kullanılıyor: indeksi test kendi
        // eşlemesiyle oluştursaydı, üretimde eşlemenin hiç uygulanmadığını
        // fark etmezdik.
        indexInitializer.ensureIndex();
        indexProduct("1", "SKU-1", "Kablosuz kulaklık", "Nike", "elektronik/ses", "1299.90", 1);
        indexProduct("2", "SKU-2", "Koşu ayakkabısı", "Nike", "giyim/ayakkabi", "2499.00", 1);
        indexProduct("3", "SKU-3", "Bluetooth hoparlör", "Adidas", "elektronik/ses", "899.50", 1);
        refresh();
    }

    private void indexProduct(String id, String sku, String name, String brand,
                              String category, String price, long version) {
        index.index(new SearchableProduct(id, sku, name, "açıklama", brand, category,
                new BigDecimal(price), "TRY", "ACTIVE", Map.of("renk", "siyah"), NOW, version));
    }

    /** Elasticsearch yazmaları anında aranabilir olmaz; test bunu beklemek yerine zorlar. */
    private void refresh() {
        elasticsearch.indexOps(IndexCoordinates.of("products")).refresh();
    }

    private SearchResult search(SearchQuery query) {
        return index.search(query);
    }

    @Test
    @DisplayName("kriter verilmezse bütün ürünler döner")
    void returnsEverythingWithoutCriteria() {
        SearchResult result = search(new SearchQuery(null, null, null, null, null, 0, 20));

        assertThat(result.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("metin araması ad ve açıklamada çalışır")
    void findsByText() {
        SearchResult result = search(new SearchQuery("kulaklık", null, null, null, null, 0, 20));

        assertThat(result.items()).singleElement()
                .extracting(SearchableProduct::sku).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName("markaya göre süzülür")
    void filtersByBrand() {
        SearchResult result = search(
                new SearchQuery(null, List.of("Nike"), null, null, null, 0, 20));

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items()).allMatch(item -> item.brand().equals("Nike"));
    }

    @Test
    @DisplayName("kategori ön ekiyle süzülür")
    void filtersByCategoryPrefix() {
        // "elektronik" seçildiğinde altındaki bütün dallar gelmeli: kategori bir ağaç.
        SearchResult result = search(
                new SearchQuery(null, null, "elektronik", null, null, 0, 20));

        assertThat(result.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("fiyat aralığıyla süzülür")
    void filtersByPriceRange() {
        SearchResult result = search(new SearchQuery(null, null, null,
                new BigDecimal("1000"), new BigDecimal("2000"), 0, 20));

        assertThat(result.items()).singleElement()
                .extracting(SearchableProduct::sku).isEqualTo("SKU-1");
    }

    @Test
    @DisplayName("facet'ler süzülmüş sonuca göre sayılır")
    void facetsFollowTheFilters() {
        SearchResult all = search(new SearchQuery(null, null, null, null, null, 0, 20));
        assertThat(all.brandFacets()).containsEntry("Nike", 2L).containsEntry("Adidas", 1L);

        SearchResult filtered = search(
                new SearchQuery(null, null, "elektronik", null, null, 0, 20));

        // Facet'ler ayrı bir sorguyla hesaplansaydı süzgeçle tutarsız kalabilirdi:
        // kullanıcı "elektronik" seçmişken giyimdeki Nike'ı sayardı.
        assertThat(filtered.brandFacets()).containsEntry("Nike", 1L).containsEntry("Adidas", 1L);
    }

    @Test
    @DisplayName("marka bütün hâliyle sayılır, kelimelere bölünmez")
    void brandFacetIsNotAnalysed() {
        indexProduct("4", "SKU-4", "Şapka", "New Balance", "giyim/aksesuar", "499.00", 1);
        refresh();

        SearchResult result = search(new SearchQuery(null, null, null, null, null, 0, 20));

        // Marka "text" olsaydı facet listesinde "new" ve "balance" diye iki satır olurdu.
        assertThat(result.brandFacets()).containsKey("New Balance");
    }

    @Test
    @DisplayName("daha eski sürüm yeni kaydın üzerine yazamaz")
    void olderVersionDoesNotOverwrite() {
        index.index(new SearchableProduct("1", "SKU-1", "Yeni ad", "açıklama", "Nike",
                "elektronik/ses", new BigDecimal("1299.90"), "TRY", "ACTIVE",
                Map.of(), NOW, 5));
        refresh();

        // Geç kalmış ya da tekrar gelen bir olay: sürümü daha küçük.
        boolean written = index.index(new SearchableProduct("1", "SKU-1", "Eski ad", "açıklama",
                "Nike", "elektronik/ses", new BigDecimal("1299.90"), "TRY", "ACTIVE",
                Map.of(), NOW, 2));
        refresh();

        assertThat(written).isFalse();
        assertThat(search(new SearchQuery("Yeni", null, null, null, null, 0, 20)).total())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("silinen ürün aramada çıkmaz")
    void deletedProductDisappears() {
        index.delete("1");
        refresh();

        assertThat(search(new SearchQuery(null, null, null, null, null, 0, 20)).total())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("sayfa boyutu sınırsız olamaz")
    void rejectsUnboundedPageSize() {
        // İstemcinin tek istekte indeksi boşaltmasını engeller.
        assertThatThrownBy(() -> new SearchQuery(null, null, null, null, null, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
