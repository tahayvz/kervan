package com.kervan.order.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Saga'nın nerede olduğu.
 *
 * <p>İzin verilen geçişler burada tanımlıdır. Tek yerde durmasının sebebi şu: saga
 * olaylara tepki verir ve olaylar <b>en az bir kez</b> gelir, bazen de sırasız. Hangi
 * geçişin geçerli olduğu kodun içine dağılsaydı, tekrar gelen bir olayın saga'yı geri
 * sarması ya da iki kez ilerletmesi mümkün olurdu.
 */
public enum SagaState {

    /** Stok ayırma komutu gönderildi, cevap bekleniyor. */
    STOCK_RESERVING,

    /** Stok ayrıldı, ödeme komutu gönderildi, cevap bekleniyor. */
    PAYMENT_PROCESSING,

    /** Ödeme başarısız oldu; tutulan stok geri bırakılıyor. */
    STOCK_RELEASING,

    /** Stok ayrıldı, ödeme alındı, sipariş onaylandı. */
    COMPLETED,

    /** Bir adım başarısız oldu ve yapılmış her şey geri alındı. */
    CANCELLED;

    private Set<SagaState> allowedNext;

    static {
        STOCK_RESERVING.allowedNext = EnumSet.of(PAYMENT_PROCESSING, CANCELLED);
        PAYMENT_PROCESSING.allowedNext = EnumSet.of(COMPLETED, STOCK_RELEASING);
        STOCK_RELEASING.allowedNext = EnumSet.of(CANCELLED);
        COMPLETED.allowedNext = EnumSet.noneOf(SagaState.class);
        CANCELLED.allowedNext = EnumSet.noneOf(SagaState.class);
    }

    public boolean canTransitionTo(SagaState next) {
        return allowedNext.contains(next);
    }

    /** Bitmiş bir saga'ya gelen olaylar yok sayılır; geri dönüş yoktur. */
    public boolean isFinal() {
        return allowedNext.isEmpty();
    }
}
