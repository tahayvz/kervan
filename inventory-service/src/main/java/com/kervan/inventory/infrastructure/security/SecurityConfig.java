package com.kervan.inventory.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Servis bir OAuth2 <b>resource server</b>'dır: token üretmez, doğrular.
 *
 * <p>Ağ geçidi kimliği doğrular, yetkiyi servisler denetler (ADR-0007). Bu dosya
 * {@code order-service}'teki ile aynı deseni izler; farkı yalnızca korunan uçlar.
 *
 * <h2>Neden bu servise güvenlik geldi</h2>
 * Bu servisin Faz 8'e kadar hiç HTTP ucu yoktu, dolayısıyla güvenlik bağımlılığı da
 * yoktu. Stok girişi ucu (ADR-0019) gelince bu bir boşluk hâline geldi: onu koruyan tek
 * şey "bu servis dışarıya açılmıyor" olurdu. Compose'da bu gerçek bir korumaydı;
 * Kubernetes'te <b>değil</b> — varsayılan ağ her şeye açıktır ve kümede NetworkPolicy
 * yok (infra/k8s/README.md'de açıkça eksik olarak yazılı). Yani ağ geçidini atlayan
 * herhangi bir pod stok yazabilirdi.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String[] API_DOCS_PATHS =
            {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    /**
     * API dokümantasyonunun kimlik doğrulamasız açılıp açılmayacağı.
     *
     * <p>Varsayılan <b>kapalı</b>: dokümantasyon, saldırgana uç listesini ve doğrulama
     * kurallarını hazır sunar. Lokal keşif için ortam değişkeniyle açılır.
     */
    @Value("${kervan.security.expose-api-docs:false}")
    private boolean exposeApiDocs;

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtRoleConverter roleConverter)
            throws Exception {
        http
                // CSRF, tarayıcı oturum çerezleri için anlamlıdır. Burada kimlik
                // Authorization başlığıyla taşınır; tarayıcı isteği otomatik
                // imzalamadığı için CSRF saldırı yüzeyi yoktur.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Actuator normalde bu portta DEĞİL, ayrı bir yönetim
                        // portunda. Bu kural, iki port tek porta indirilirse sağlık
                        // ucunun kapanmaması için duruyor: kapanırsa Kubernetes
                        // probe'ları 401 alır ve pod hiç hazır olmaz.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(API_DOCS_PATHS).access(
                                (authentication, context) -> new AuthorizationDecision(exposeApiDocs))
                        // Stok YALNIZCA yöneticinindir — okuma da dâhil.
                        //
                        // Girişin yönetici işi olması açık. Okumanın da kapalı olması
                        // bilinçli: kalan stok ticari bilgidir. "Son 2 adet" bilgisi
                        // rakibe fiyatlama ipucu, kötü niyetliye ise stok tüketme
                        // saldırısı için hedef verir.
                        //
                        // Müşteriye stok göstermek gerekirse o, bu uç açılarak değil,
                        // katalogda türetilmiş bir alanla ("stokta var / yok") yapılır.
                        .requestMatchers("/api/v1/stock/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // Varsayılan dönüştürücü Keycloak'ın realm_access.roles alanını
                // görmez; kendi dönüştürücümüz olmadan her istek 403 dönerdi.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(roleConverter)));

        return http.build();
    }

    /**
     * Yönetim portundaki actuator uçları açıktır.
     *
     * <p>Burayı koruyan şey ağdır, token değil: bu port dışarıya açılmaz, Prometheus
     * ağın içinden okur (ADR-0014).
     *
     * <p>{@code @ConditionalOnManagementPort(DIFFERENT)} bir emniyet kilidi: biri yönetim
     * portunu iş portuyla birleştirirse bu bean <b>kaybolur</b> ve actuator yeniden ana
     * zincirin kurallarına tabi olur. Aksi hâlde tek satırlık bir port değişikliği metrik
     * ucunu sessizce herkese açardı.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnManagementPort(ManagementPortType.DIFFERENT)
    SecurityFilterChain managementFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
