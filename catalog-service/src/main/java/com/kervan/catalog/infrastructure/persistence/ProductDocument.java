package com.kervan.catalog.infrastructure.persistence;

import com.kervan.catalog.domain.model.ProductStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * MongoDB kalıcılık modeli — {@code products} koleksiyonu.
 * <p>
 * Domain {@code Product}'tan bilinçli olarak ayrıdır: kalıcılık anotasyonları
 * (@Document, @Id, @Version) burada yaşar, domain saf kalır (hexagonal).
 * {@code attributes} serbest bir {@code Map}'tir — MongoDB'nin esnek belge modelini
 * kullanmamızın nedeni budur (ADR-0006).
 * <p>
 * İndeksler burada anotasyonla DEĞİL, Mongock migration'ıyla (V001ProductIndexes)
 * yönetilir — şema/indeks tek bir yerden, versiyonlu (Flyway mantığı).
 */
@Document(collection = "products")
public class ProductDocument {

    @Id
    private String id;
    private String sku;
    private String name;
    private String description;
    private String brand;
    private String categoryPath;
    private BigDecimal priceAmount;
    private String priceCurrency;
    private ProductStatus status;
    private Map<String, Object> attributes;
    private Instant createdAt;
    private Instant updatedAt;

    @Version
    private Long version; // iyimser kilitleme: eşzamanlı güncellemede çakışmayı yakalar

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getCategoryPath() { return categoryPath; }
    public void setCategoryPath(String categoryPath) { this.categoryPath = categoryPath; }
    public BigDecimal getPriceAmount() { return priceAmount; }
    public void setPriceAmount(BigDecimal priceAmount) { this.priceAmount = priceAmount; }
    public String getPriceCurrency() { return priceCurrency; }
    public void setPriceCurrency(String priceCurrency) { this.priceCurrency = priceCurrency; }
    public ProductStatus getStatus() { return status; }
    public void setStatus(ProductStatus status) { this.status = status; }
    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
