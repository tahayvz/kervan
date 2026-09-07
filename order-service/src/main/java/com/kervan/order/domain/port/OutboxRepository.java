package com.kervan.order.domain.port;

import com.kervan.order.domain.model.OutboxMessage;

import java.time.Instant;
import java.util.List;

/**
 * Outbox kayıtlarının kalıcılık sözleşmesi.
 * <p>
 * {@link #save} çağrısı, siparişi kaydeden transaction'ın <b>içinde</b> yapılmalıdır;
 * outbox'ın tüm değeri bu atomikliktir.
 */
public interface OutboxRepository {

    OutboxMessage save(OutboxMessage message);

    /**
     * Gönderilmeyi bekleyen kayıtları kilitleyerek okur; çağıran transaction bitene
     * kadar başka bir kopya aynı satırları almaz.
     *
     * @param limit       tek turda taşınacak azami kayıt
     * @param maxAttempts bu sayıya ulaşmış kayıtlar kenara alınır ve dönülmez
     */
    List<OutboxMessage> lockDeliverable(int limit, int maxAttempts);

    /** Yalnızca okuma — izleme ve testler için; kilit almaz. */
    List<OutboxMessage> findUnpublished(int limit);

    void markPublished(String id, Instant publishedAt);

    /** Başarısız denemeyi sayaca işler; sınıra ulaşan kayıt bir daha denenmez. */
    void recordFailedAttempt(String id, Instant at, String error);

    /**
     * Yayınlandığı <b>işaretlenmiş</b> ve verilen andan eski kayıtları siler.
     *
     * <p>Uygulama içi yayıncı devredeyken kullanılır: orada teslim edilen her kayıt
     * {@code publishedAt} ile damgalanır, yani silinebilir olanı işaretten anlarız.
     * Damgasız eski kayıtlar dokunulmadan kalır — onlar gönderilememiş kayıtlardır ve
     * silinmeleri olayın kaybolması demek olurdu.
     *
     * @param batchSize tek seferde silinecek azami satır; büyük bir DELETE tabloyu
     *                  uzun süre kilitler
     * @return silinen satır sayısı
     */
    int deletePublishedBefore(Instant cutoff, int batchSize);

    /**
     * Verilen andan eski <b>tüm</b> kayıtları siler.
     *
     * <p>Debezium devredeyken kullanılır. Orada kimse {@code publishedAt} damgalamaz —
     * Debezium satırı veritabanının değişiklik günlüğünden okur, tabloya hiç dokunmaz.
     * Bu yüzden ölçüt "yayınlandı mı" olamaz, yaş olmak zorundadır.
     *
     * <p><b>Buradaki risk:</b> Debezium, saklama penceresinden daha uzun süre durursa
     * henüz okumadığı satırlar silinir ve o olaylar kaybolur. Pencere bu yüzden geniş
     * tutulur ve Debezium'un durduğu izlenir (replication slot gecikmesi;
     * {@code infra/docker/debezium/README.md}).
     *
     * @return silinen satır sayısı
     */
    int deleteAllBefore(Instant cutoff, int batchSize);
}
