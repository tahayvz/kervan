package com.kervan.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Hız sınırlamasının <b>kime</b> uygulanacağını belirler.
 *
 * <h2>Neden Redis?</h2>
 * Ağ geçidi birden fazla kopya hâlinde çalışır. Sayaç her kopyanın belleğinde olsaydı
 * her biri kendi sınırını uygular, üç kopyada sınır üçe katlanırdı. Sayacın
 * paylaşılması gerekiyor ve burada Redis doğru araç: sayaç kısa ömürlü, çok sık
 * yazılan ve kaybolması felaket olmayan bir veridir.
 *
 * <h2>Anahtar seçimi</h2>
 * Kimliği bilinen istek <b>kullanıcıya</b>, bilinmeyen istek <b>IP adresine</b> göre
 * sınırlanır.
 *
 * <p>Yalnızca IP kullanılsaydı, aynı kurumsal ağdan çıkan bütün kullanıcılar tek bir
 * kotayı paylaşırdı — biri diğerlerini kilitlerdi. Yalnızca kullanıcı kullanılsaydı
 * giriş yapmamış trafiği hiç sınırlayamazdık; oysa korunması gereken asıl yüzey
 * (açık arama ve katalog uçları) tam olarak orası.
 *
 * <p><b>IP'nin bilinen zayıflığı:</b> ağ geçidi bir yük dengeleyicinin arkasındaysa
 * gördüğü adres dengeleyicinin adresidir ve herkes tek kotaya düşer. Üretimde bunun
 * çözümü, güvenilen bir {@code X-Forwarded-For} başlığını okumaktır; ama o başlık
 * yalnızca dengeleyicinin yazdığına güvenilebiliyorsa okunabilir — aksi hâlde istemci
 * başlığı uydurup sınırı sıfırlar. Bu proje dengeleyicisiz çalıştığı için doğrudan
 * bağlantı adresi kullanılıyor.
 */
@Configuration
class RateLimitConfig {

    /** Kimlik yoksa ve adres de okunamıyorsa hepsi tek kovaya düşer; sınırsız kalmaz. */
    private static final String UNKNOWN_CLIENT = "bilinmeyen";

    @Bean
    KeyResolver rateLimitKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
                .map(RateLimitConfig::subjectOf)
                .filter(subject -> !subject.isBlank())
                .switchIfEmpty(Mono.fromSupplier(() -> clientAddressOf(exchange)));
    }

    private static String subjectOf(SecurityContext context) {
        if (context.getAuthentication() instanceof JwtAuthenticationToken token) {
            Jwt jwt = token.getToken();
            return "user:" + jwt.getSubject();
        }
        return "";
    }

    private static String clientAddressOf(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote == null || remote.getAddress() == null) {
            return "ip:" + UNKNOWN_CLIENT;
        }
        return "ip:" + remote.getAddress().getHostAddress();
    }
}
