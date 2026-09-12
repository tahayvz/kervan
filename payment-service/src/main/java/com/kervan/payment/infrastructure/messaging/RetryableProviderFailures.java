package com.kervan.payment.infrastructure.messaging;

import com.kervan.payment.domain.model.PaymentProviderUnavailableException;

import java.util.function.Predicate;

/**
 * Hangi hatanın tekrar denenebileceğine karar verir.
 *
 * <h2>Neden ayarda tip listesi yetmiyor?</h2>
 * "Şu sınıfı tekrar dene" demek burada yeterli değil, çünkü aynı sınıfın iki
 * farklı anlamı var:
 *
 * <ul>
 *   <li>İstek sağlayıcıya <b>hiç ulaşmadı</b> → tekrar denemek güvenli.</li>
 *   <li>İstek gitti ama cevap gelmedi → tahsilat yapılmış <em>olabilir</em>.
 *       Körlemesine tekrar denemek <b>ikinci kez para çekmek</b> demektir.</li>
 * </ul>
 *
 * <p>Para söz konusu olduğunda varsayılan davranış "tekrar dene" olamaz.
 * Burada tekrar deneme yalnızca ilk durumda açılır; ikincisinde saga kendi
 * telafi yolunu işletir ve ödeme başarısız sayılır. Yanlış tarafta hata yapmak
 * müşteriye iki kez para çekmektir; bu, siparişin iptal olmasından çok daha
 * pahalıdır.
 *
 * <p>Not: sağlayıcı sipariş kimliğine göre idempotent olsaydı ikinci durumda da
 * tekrar denenebilirdi. Taklit sağlayıcımız bunu yapıyor ama gerçek bir
 * sağlayıcının yaptığını <em>varsaymıyoruz</em>; varsayım yanlışsa bedeli
 * müşterinin cebinden çıkar.
 *
 * <p>Reddedilme hiçbir koşulda tekrar denenmez: sağlayıcı çalışıyor ve cevabı
 * "hayır". Aynı isteği tekrar göndermek aynı cevabı alır.
 */
public class RetryableProviderFailures implements Predicate<Throwable> {

    @Override
    public boolean test(Throwable throwable) {
        return throwable instanceof PaymentProviderUnavailableException failure
                && !failure.mayHaveBeenProcessed();
    }
}
