package com.kervan.catalog.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Katalog servisinin yetki denetimi.
 *
 * <p><b>Neden sonradan eklendi:</b> servis hiçbir denetim yapmıyordu. Doğrudan
 * erişilebildiği sürece bu bir tercih sayılabilirdi. Ama ağ geçidi eklendiğinde
 * (ADR-0007) katalog belgelenmiş ön kapının arkasına kondu ve ağ geçidi yalnızca
 * <em>kimlik</em> doğruluyordu. Sonuç: CUSTOMER rolüyle alınmış herhangi bir token
 * {@code DELETE /api/v1/products/{id}} çağırıp ürünü kalıcı silebiliyordu.
 *
 * <p>ADR-0007 bu şartı zaten yazıyordu: <em>"Denetimsiz bir servisi ağ geçidine
 * bağlamak, onu kimliği doğrulanmış herkese açar."</em> Bu sınıf o şartı karşılıyor.
 *
 * <p><b>Okuma neden herkese açık:</b> ürün listesi bir vitrindir, görmek için hesap
 * gerekmez. Ağ geçidindeki kural da aynı; ikisi aynı şeyi söylemeli.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final String PRODUCTS = "/api/v1/products/**";

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // Kimlik Authorization basligiyla tasinir; tarayici bu basligi
                // otomatik eklemedigi icin CSRF saldiri yuzeyi yoktur.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        // Okuma herkese acik.
                        .requestMatchers(HttpMethod.GET, PRODUCTS).permitAll()
                        // Katalogu DEGISTIREN her sey yonetici ister: olusturma,
                        // guncelleme, yayina alma, arsivleme, kalici silme.
                        .requestMatchers(PRODUCTS).hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(keycloakRoleConverter())));

        return http.build();
    }

    /**
     * Keycloak'ın rol yerleşimini Spring Security yetkilerine çevirir.
     *
     * <p>Keycloak rolleri {@code realm_access.roles} altında düz bir liste olarak
     * taşır; Spring ise {@code ROLE_} önekli yetki bekler. Varsayılan dönüştürücü
     * {@code scope} claim'ine bakar ve bu rolleri göremez — bu olmadan yönetici
     * bile 403 alır.
     *
     * <p>order-service'te aynı işi yapan ayrı bir sınıf var. Burada sınıf yerine
     * kısa bir metot tercih edildi: üçüncü bir servis de aynısına ihtiyaç duyarsa
     * ortak bir modüle çıkarmanın zamanı gelmiş demektir.
     */
    private Converter<Jwt, AbstractAuthenticationToken> keycloakRoleConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities =
                    new JwtGrantedAuthoritiesConverter().convert(jwt);
            Set<GrantedAuthority> result = authorities == null
                    ? new java.util.HashSet<>()
                    : new java.util.HashSet<>(authorities);

            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
                result.addAll(roles.stream()
                        .map(String::valueOf)
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toSet()));
            }
            return result;
        });
        return converter;
    }
}
