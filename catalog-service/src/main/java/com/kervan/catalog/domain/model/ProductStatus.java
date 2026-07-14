package com.kervan.catalog.domain.model;

/**
 * Ürün yaşam döngüsü durumu.
 * <ul>
 *   <li>{@code DRAFT}    — yeni oluşturuldu, henüz yayında değil</li>
 *   <li>{@code ACTIVE}   — yayında, satışa/aramaya açık</li>
 *   <li>{@code ARCHIVED} — kaldırıldı, aramada görünmez (ama kaydı korunur)</li>
 * </ul>
 * Durum geçişleri {@link Product} içinde kurallıdır (örn. DRAFT/ARCHIVED → ACTIVE).
 */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED
}
