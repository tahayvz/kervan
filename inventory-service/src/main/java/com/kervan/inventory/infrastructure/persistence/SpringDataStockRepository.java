package com.kervan.inventory.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /**
     * SKU için sıfır miktarlı satır açar; satır zaten varsa hiçbir şey yapmaz.
     *
     * <p>Mal kabulü hiç görülmemiş bir SKU için de gelebilir. "Önce sorgula, yoksa
     * ekle" yazılsaydı aynı yeni SKU'ya gelen iki makbuz da satırı göremez, ikisi de
     * eklemeye kalkar ve biri birincil anahtara takılırdı. Koşulu veritabanına
     * yaptırmak, iki adımın arasına başka bir isteğin giremeyeceği tek yoldur.
     *
     * <p>Miktar sıfır açılıyor: satırın açılması "mal geldi" demek değildir, yalnızca
     * defterde yer açmaktır. Miktarı ekleyen şey makbuzun kendisidir.
     */
    @Modifying
    @Query(value = "INSERT INTO stock_items (sku, available_quantity, reserved_quantity, updated_at) "
            + "VALUES (:sku, 0, 0, :now) ON CONFLICT (sku) DO NOTHING",
            nativeQuery = true)
    void insertIfAbsent(@Param("sku") String sku, @Param("now") Instant now);
}
