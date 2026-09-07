package com.kervan.inventory.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface SpringDataStockRepository extends JpaRepository<StockItemEntity, String> {

    /**
     * Satırları kilitleyerek ve <b>her zaman aynı sırada</b> okur.
     *
     * <p>Kilit, "oku–hesapla–yaz" arasında başka bir transaction'ın araya girmesini
     * engeller: iki sipariş son ürünü aynı anda okuyup ikisi de satamaz.
     *
     * <p>{@code ORDER BY sku} sıradan bir düzen tercihi değil, <b>deadlock önlemidir</b>.
     * A ve B ürünlerini içeren iki sipariş farklı sıralarla kilit alsaydı, biri A'yı
     * diğeri B'yi tutar ve ikisi de diğerini beklerdi. Sabit sıra bu durumu yapısal
     * olarak imkânsız kılar; yeniden deneme mantığı gerekmez.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockItemEntity s WHERE s.sku IN :skus ORDER BY s.sku ASC")
    List<StockItemEntity> lockBySkus(@Param("skus") Collection<String> skus);
}
