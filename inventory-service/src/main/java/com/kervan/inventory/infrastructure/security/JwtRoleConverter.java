package com.kervan.inventory.infrastructure.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keycloak'ın rol yerleşimini Spring Security yetkilerine çevirir.
 * <p>
 * Keycloak rolleri {@code realm_access.roles} altında düz bir liste olarak taşır;
 * Spring ise {@code ROLE_} önekli {@link GrantedAuthority} bekler. Varsayılan
 * dönüştürücü {@code scope} claim'ine bakar ve bu rolleri göremez — bu sınıf olmadan
 * her istek 403 döner.
 */
@Component
class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        return new JwtAuthenticationToken(jwt, authorities(jwt), subject(jwt));
    }

    private Collection<GrantedAuthority> authorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim(REALM_ACCESS);
        if (realmAccess == null || !(realmAccess.get(ROLES) instanceof Collection<?> roles)) {
            return Set.of();
        }

        return roles.stream()
                .map(String::valueOf)
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Kullanıcı kimliği olarak {@code preferred_username} yerine {@code sub} kullanılır:
     * kullanıcı adı değişebilir, {@code sub} değişmez.
     */
    private String subject(Jwt jwt) {
        return jwt.getSubject();
    }
}
