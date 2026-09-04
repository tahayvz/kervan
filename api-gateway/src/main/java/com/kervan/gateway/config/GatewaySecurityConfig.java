package com.kervan.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Ağ geçidinin güvenlik yapılandırması.
 *
 * <p><b>Ağ geçidi KİMLİK DOĞRULAR, YETKİ DENETLEMEZ.</b> Bu ayrım bilinçlidir ve
 * projenin en önemli güvenlik kararlarından biridir (ADR-0007).
 *
 * <ul>
 *   <li><b>Burada:</b> "Bu token geçerli mi?" Geçersizse istek daha servislere
 *       ulaşmadan 401 ile döner. Kimliksiz trafik kenarda kesilir.</li>
 *   <li><b>Serviste:</b> "Bu kullanıcı bunu yapabilir mi?" Rol denetimi ve kayıt
 *       bazlı sahiplik ({@code Caller.owns()}) order-service'te kalır.</li>
 * </ul>
 *
 * <p><b>Yetki denetimi neden buraya taşınmadı?</b> İki sebep:
 *
 * <ol>
 *   <li><b>Kural iki yere yazılırsa kaçınılmaz olarak birbirinden kayar.</b> Bugün
 *       aynı olan iki liste, altı ay sonra farklı olur ve hangisinin doğru olduğu
 *       belli olmaz.</li>
 *   <li><b>Ağ geçidi kayıt bazlı sahipliği zaten yapamaz.</b> "Bu sipariş SENİN mi?"
 *       sorusunun cevabı veritabanındadır. Ağ geçidi veriyi görmez. Yetkinin bir
 *       kısmı zorunlu olarak serviste kalacaksa, tamamı serviste kalmalıdır.</li>
 * </ol>
 *
 * <p>Token aşağı taşınır: ağ geçidi {@code Authorization} başlığını olduğu gibi
 * iletir, servisler de kendi doğrulamalarını yapar. Ağ geçidi tek savunma hattı
 * değildir; ağ içinden gelen bir istek servise doğrudan ulaşabilir.
 */
@Configuration
@EnableWebFluxSecurity
class GatewaySecurityConfig {

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http) {
        http
                // Kimlik Authorization başlığıyla taşınır. Tarayıcı bu başlığı
                // otomatik eklemediği için CSRF saldırı yüzeyi yoktur.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(exchange -> exchange
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Katalog okuması herkese açık: ürün listesi bir vitrindir,
                        // görmek için hesap gerekmez. Servisin bugünkü davranışı da
                        // budur; ağ geçidi onu değiştirmemeli.
                        .pathMatchers(HttpMethod.GET, "/api/v1/products/**").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }
}
