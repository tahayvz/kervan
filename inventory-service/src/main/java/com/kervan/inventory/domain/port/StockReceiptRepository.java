package com.kervan.inventory.domain.port;

import com.kervan.inventory.domain.model.StockReceipt;

/**
 * Mal kabul makbuzlarının deposu.
 *
 * <p>Bu deponun asıl işi kayıt tutmak değil, <b>tekrarı durdurmaktır</b>. HTTP isteği
 * de Kafka mesajı gibi tekrarlanabilir: istemci zaman aşımı alır ve yeniden dener,
 * oysa ilk istek işlenmiştir. Makbuz kimliği birincil anahtar olduğu için ikinci
 * yazma veritabanında durur.
 */
public interface StockReceiptRepository {

    /**
     * Makbuzu kaydeder; aynı kimlik zaten varsa <b>yazmaz</b>.
     *
     * @return ilk kez kaydedildiyse {@code true}, tekrar ise {@code false}
     */
    boolean saveIfNew(StockReceipt receipt);
}
