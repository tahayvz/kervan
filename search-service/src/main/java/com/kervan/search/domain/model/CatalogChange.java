package com.kervan.search.domain.model;

/**
 * Katalogta olan bir değişiklik.
 *
 * <p>Kapalı bir hiyerarşi ({@code sealed}): katalogtan gelebilecek iki durum var ve
 * ikisi de burada. Yeni bir durum eklendiğinde onu işlemeyen her {@code switch}
 * derleme zamanında hata verir — çalışma anında sessizce atlanmaz.
 */
public sealed interface CatalogChange {

    String productId();

    /** Ürün eklendi ya da güncellendi; indekste bu hâliyle durmalı. */
    record Upserted(SearchableProduct product) implements CatalogChange {
        @Override
        public String productId() {
            return product.id();
        }
    }

    /** Ürün silindi; indeksten çıkmalı. */
    record Deleted(String productId) implements CatalogChange {
    }
}
