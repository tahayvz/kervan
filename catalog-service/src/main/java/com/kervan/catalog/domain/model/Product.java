package com.kervan.catalog.domain.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Ürün — katalog bounded context'inin aggregate root'u.
 * <p>
 * <b>Neden MongoDB / document?</b> {@link #attributes} alanı kategoriye göre değişen
 * serbest özniteliklerdir (ayakkabı → numara/renk; kitap → ISBN/yazar). İlişkisel
 * şemada bu ya yüzlerce nullable kolon ya EAV anti-deseni doğururdu (bkz. ADR-0006).
 * Belge modelinde her ürün kendi alanlarını taşır.
 * <p>
 * <b>Framework'süz:</b> Bu sınıf saf domain'dir; Spring/Mongo anotasyonu içermez.
 * Kalıcılık, {@code infrastructure.persistence.ProductDocument} ile eşlenir. Bu ayrım
 * iş kurallarını test edilebilir ve teknolojiden bağımsız tutar (hexagonal mimari).
 */
public class Product {

    private final String id;          // yeni ürün kaydedilene kadar null
    private final String sku;         // değişmez iş anahtarı (stock keeping unit)
    private String name;
    private String description;
    private String brand;
    private String categoryPath;      // örn. "elektronik/telefon/akilli-telefon"
    private Money price;
    private ProductStatus status;
    private final Map<String, Object> attributes;
    private final Instant createdAt;
    private Instant updatedAt;
    private final Long version;        // iyimser kilitleme (optimistic locking)

    private Product(String id, String sku, String name, String description, String brand,
                    String categoryPath, Money price, ProductStatus status,
                    Map<String, Object> attributes, Instant createdAt, Instant updatedAt,
                    Long version) {
        this.id = id;
        this.sku = sku;
        this.name = name;
        this.description = description;
        this.brand = brand;
        this.categoryPath = categoryPath;
        this.price = price;
        this.status = status;
        this.attributes = new HashMap<>(attributes == null ? Map.of() : attributes);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    /**
     * Yeni ürün oluşturur. Durum {@code DRAFT} başlar; yayına almak için
     * {@link #activate()} çağrılır. Zaman damgaları burada set edilir.
     */
    public static Product create(String sku, String name, String description, String brand,
                                 String categoryPath, Money price, Map<String, Object> attributes) {
        validateRequired("sku", sku);
        validateRequired("name", name);
        validateRequired("brand", brand);
        validateRequired("categoryPath", categoryPath);
        if (price == null) {
            throw new IllegalArgumentException("price null olamaz");
        }
        Instant now = Instant.now();
        return new Product(null, sku, name, description, brand, categoryPath, price,
                ProductStatus.DRAFT, attributes, now, now, null);
    }

    /**
     * Kalıcılıktan (MongoDB) yeniden inşa (mapper kullanır). Doğrulama uygulamaz;
     * çünkü veri zaten geçerli bir şekilde kaydedilmişti.
     */
    public static Product reconstitute(String id, String sku, String name, String description,
                                       String brand, String categoryPath, Money price,
                                       ProductStatus status, Map<String, Object> attributes,
                                       Instant createdAt, Instant updatedAt, Long version) {
        return new Product(id, sku, name, description, brand, categoryPath, price, status,
                attributes, createdAt, updatedAt, version);
    }

    /* ------------------------- Davranışlar (iş kuralları) ------------------------- */

    public void updateDetails(String name, String description, String categoryPath,
                              Map<String, Object> attributes) {
        validateRequired("name", name);
        validateRequired("categoryPath", categoryPath);
        this.name = name;
        this.description = description;
        this.categoryPath = categoryPath;
        this.attributes.clear();
        if (attributes != null) {
            this.attributes.putAll(attributes);
        }
        touch();
    }

    public void changePrice(Money newPrice) {
        if (newPrice == null) {
            throw new IllegalArgumentException("price null olamaz");
        }
        this.price = newPrice;
        touch();
    }

    /** Yayına alır. ARCHIVED veya DRAFT → ACTIVE geçişine izin verilir. */
    public void activate() {
        this.status = ProductStatus.ACTIVE;
        touch();
    }

    /** Arşivler. Kayıt korunur ama aramada görünmez. */
    public void archive() {
        this.status = ProductStatus.ARCHIVED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    private static void validateRequired(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " boş olamaz");
        }
    }

    /* --------------------------------- Getters --------------------------------- */

    public String getId() { return id; }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getBrand() { return brand; }
    public String getCategoryPath() { return categoryPath; }
    public Money getPrice() { return price; }
    public ProductStatus getStatus() { return status; }
    public Map<String, Object> getAttributes() { return Map.copyOf(attributes); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
