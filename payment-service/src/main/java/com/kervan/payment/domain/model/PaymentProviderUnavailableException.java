package com.kervan.payment.domain.model;

/**
 * Sağlayıcıya ulaşılamadı ya da sağlayıcı cevap veremedi.
 *
 * <h2>Neden {@link PaymentDeclinedException}'dan ayrı?</h2>
 * İkisi taban tabana zıt iki durumdur ve karıştırılmaları pahalıya mal olur:
 *
 * <ul>
 *   <li><b>Reddedilme</b> işin normal bir sonucudur. Sağlayıcı çalışıyor ve
 *       "hayır" diyor. Tekrar denemek anlamsızdır; cevap değişmez.</li>
 *   <li><b>Erişilememe</b> bir arızadır. Cevap yok. Tekrar denemek anlamlı
 *       olabilir.</li>
 * </ul>
 *
 * <p>Bu ayrım devre kesicinin doğru çalışması için şarttır. Reddedilmeler
 * başarısızlık sayılsaydı, meşru bir ret dalgası — örneğin bir kampanya sonrası
 * limit aşımları ya da bir dolandırıcılık dalgası — kesiciyi açar ve <b>çalışan</b>
 * bir sağlayıcıya giden bütün ödemeler kesilirdi. Sistem, hiçbir şey bozuk
 * değilken kendini kapatırdı.
 *
 * <h2>{@link #mayHaveBeenProcessed()} neden var?</h2>
 * "Ulaşamadım" iki farklı şey olabilir:
 *
 * <ul>
 *   <li><b>İstek hiç gitmedi</b> (bağlantı reddedildi, DNS çözülmedi). Sağlayıcı
 *       bu isteği görmedi; tekrar denemek güvenlidir.</li>
 *   <li><b>İstek gitti, cevap gelmedi</b> (okuma zaman aşımı). Tahsilat yapılmış
 *       <em>olabilir</em>. Körlemesine tekrar denemek ikinci kez para çekmek
 *       demektir.</li>
 * </ul>
 *
 * <p>İkinci durumda tekrar denemenin güvenli olmasının tek yolu sağlayıcının
 * <b>aynı siparişi iki kez tahsil etmemesidir</b> (idempotentlik). Bu projede
 * taklit sağlayıcı bunu sipariş kimliğine göre yapıyor; gerçek bir sağlayıcıda
 * karşılığı "idempotency key" başlığıdır.
 */
public class PaymentProviderUnavailableException extends RuntimeException {

    private final boolean mayHaveBeenProcessed;

    public PaymentProviderUnavailableException(String message, boolean mayHaveBeenProcessed) {
        super(message);
        this.mayHaveBeenProcessed = mayHaveBeenProcessed;
    }

    public PaymentProviderUnavailableException(String message, boolean mayHaveBeenProcessed,
                                               Throwable cause) {
        super(message, cause);
        this.mayHaveBeenProcessed = mayHaveBeenProcessed;
    }

    /** @return istek sağlayıcıya ulaşmış olabilir mi; true ise tekrar deneme tehlikelidir */
    public boolean mayHaveBeenProcessed() {
        return mayHaveBeenProcessed;
    }
}
