package com.kervan.order.web;

import com.kervan.order.domain.model.Caller;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Doğrulanmış token'ı domain'in {@link Caller} tipine çevirir.
 * <p>
 * Bu dönüşüm web katmanında yapılır; böylece domain, Spring Security tiplerini
 * tanımaz ve sahiplik kuralları çıplak JUnit ile test edilebilir kalır.
 */
final class CallerMapper {

    private static final String ROLE_PREFIX = "ROLE_";

    private CallerMapper() {
    }

    static Caller from(JwtAuthenticationToken token) {
        Set<String> roles = token.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());

        return new Caller(token.getName(), roles);
    }
}
