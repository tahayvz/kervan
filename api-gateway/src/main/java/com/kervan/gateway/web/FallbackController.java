package com.kervan.gateway.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Devre kesici açıkken istemciye dönen cevap.
 *
 * <h2>Neden bir cevap veriyoruz da hata fırlatmıyoruz?</h2>
 * Arka servis erişilemezken istemcinin bunu <b>hızlı ve anlaşılır</b> öğrenmesi
 * gerekir. Alternatif, isteğin zaman aşımına kadar beklemesidir: istemci de
 * bekler, ağ geçidinde de kaynak tutulur ve çöken servise yük binmeye devam eder.
 *
 * <h2>Neden 503, 500 değil?</h2>
 * 500 "bir hata yaptım" der; burada olan bu değil. 503 "şu an hizmet veremiyorum,
 * sonra tekrar dene" der ve {@code Retry-After} başlığıyla ne kadar sonra
 * deneneceğini söyler. Fark, istemcinin ne yapacağını belirler: 500'de tekrar
 * denemek anlamsız, 503'te anlamlıdır.
 *
 * <h2>Neden sahte veri dönmüyoruz?</h2>
 * Bazı sistemlerde geri düşüş (fallback) önbellekten eski veri döndürür. Katalog
 * için bu düşünülebilirdi ama sipariş için felaket olurdu: "siparişin yok" cevabı,
 * "şu an bakamıyorum" ile aynı şey değildir. Tek bir kural tutuldu — <b>hiçbir
 * rota uydurma veri dönmez</b>; eksik cevap, yanlış cevaptan iyidir.
 */
@RestController
@RequestMapping("/fallback")
class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    /** {@code waitDurationInOpenState} ile aynı: istemciye boşuna erken deneme dedirtmeyelim. */
    private static final String RETRY_AFTER_SECONDS = "10";

    @GetMapping("/{service}")
    ResponseEntity<ProblemDetail> unavailable(@PathVariable String service) {
        // Uyarı seviyesinde: bu satır "arka servis cevap vermiyor" demektir ve
        // devre kesicinin metrikleriyle birlikte okunur.
        log.warn("Devre kesici devrede, istek arka servise gönderilmedi: servis={}", service);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setType(URI.create("https://kervan.dev/problems/service-unavailable"));
        problem.setTitle("Servis şu an yanıt veremiyor");
        problem.setDetail("'%s' servisi geçici olarak erişilemiyor. Lütfen biraz sonra tekrar deneyin."
                .formatted(service));
        // Hangi servis olduğu gövdede duruyor; istemci buna göre kısmi arayüz
        // gösterebilir (örneğin arama kapalıyken katalog açık kalabilir).
        problem.setProperty("service", service);

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem);
    }
}
