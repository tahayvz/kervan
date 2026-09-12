package com.kervan.order.infrastructure.security;

import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.autoconfigure.web.server.ConditionalOnManagementPort;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Servis bir OAuth2 <b>resource server</b>'dır: token üretmez, doğrular.
 * <p>
 * Kimlik doğrulama Keycloak'a devredilmiştir (ADR-0001'deki servis sınırı mantığı:
 * her servis kendi işini yapar). Bu servisin görevi gelen JWT'nin imzasını doğrulamak
 * ve içindeki rolleri Spring Security yetkilerine çevirmektir.
 * <p>
 * <b>Neden stateless?</b> Oturum tutulmaz. Sipariş servisi yatayda çoğaltılabilir
 * olmalı; oturum durumu tutan bir servis, isteklerin aynı örneğe gitmesini gerektirir
 * ya da paylaşılan bir oturum deposu ister. JWT ile her istek kendi kimliğini taşır.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String[] API_DOCS_PATHS =
            {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    /**
     * API dokümantasyonunun kimlik doğrulamasız açılıp açılmayacağı.
     * <p>
     * Varsayılan <b>kapalı</b>: dokümantasyon, saldırgana uç listesini, alan adlarını ve
     * doğrulama kurallarını hazır sunar. Lokal keşif için ortam değişkeniyle açılır;
     * üretimde açık bırakılmaz.
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
                        // portunda (bkz. application.yml). Bu kural, iki port tek
                        // porta indirilirse sağlık ucunun kapanmaması için duruyor:
                        // kapanırsa yük dengeleyici servisi ölü sanır.
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(API_DOCS_PATHS).access(
                                (authentication, context) ->
                                        new org.springframework.security.authorization.AuthorizationDecision(
                                                exposeApiDocs))
                        // Sipariş oluşturmak müşteri rolü ister; okuma yetkisi
                        // ayrıca kayıt bazında OrderService içinde denetlenir.
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders")
                            .hasAnyRole("CUSTOMER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**")
                            .hasAnyRole("CUSTOMER", "ADMIN")
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
     * ağın içinden okur. Kubernetes'te de aynı desen kullanılır — probe ve kazıma
     * (scrape) trafiği iş trafiğiyle aynı kapıdan geçmez.
     *
     * <p>{@code @ConditionalOnManagementPort(DIFFERENT)} bir emniyet kilidi: biri
     * yönetim portunu iş portuyla birleştirirse bu bean <b>kaybolur</b> ve actuator
     * yeniden ana zincirin kurallarına, yani kimlik doğrulamasına tabi olur. Aksi
     * hâlde tek satırlık bir port değişikliği metrik ucunu sessizce herkese açardı.
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
