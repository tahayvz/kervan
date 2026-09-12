package com.kervan.inventory.security;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Testler için gerçek imzalı JWT üretir.
 *
 * <p><b>Neden Keycloak ayağa kaldırılmıyor?</b> Doğrulanmak istenen şey Keycloak'ın
 * çalıştığı değil, <em>bu servisin</em> geçerli imzayı kabul edip geçersizi reddettiği
 * ve rolleri doğru okuduğudur. Anahtar çifti test çalışırken üretilir; böylece repoya
 * hiçbir özel anahtar girmez ve testler ağa bağımlı olmaz.
 *
 * <p>{@link JwtDecoder} bean'i burada tanımlandığı için Spring Boot'un
 * otomatik yapılandırması devreye girmez ve {@code jwk-set-uri} adresine istek atılmaz.
 */
@TestConfiguration
public class TestJwtSupport {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Test anahtar çifti üretilemedi", e);
        }
    }

    @Bean
    JwtDecoder testJwtDecoder() {
        return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
    }

    /** Verilen kullanıcı ve rollerle geçerli bir token üretir. */
    public static String tokenFor(String userId, String... roles) {
        return sign(claims(userId, roles).build());
    }

    /** Süresi geçmiş token — reddedilmesi gerekir. */
    public static String expiredTokenFor(String userId, String... roles) {
        Instant past = Instant.now().minusSeconds(7200);
        return sign(claims(userId, roles)
                .issueTime(Date.from(past))
                .expirationTime(Date.from(past.plusSeconds(60)))
                .build());
    }

    /** Başka bir anahtarla imzalanmış token — imza doğrulaması başarısız olmalı. */
    public static String tokenSignedByStranger(String userId, String... roles) {
        try {
            KeyPair foreign = generateKeyPair();
            SignedJWT jwt = new SignedJWT(header(), claims(userId, roles).build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) foreign.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Token imzalanamadı", e);
        }
    }

    private static JWTClaimsSet.Builder claims(String userId, String... roles) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(userId)
                .jwtID(UUID.randomUUID().toString())
                .issuer("https://test-issuer.kervan.local")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(600)))
                // Keycloak rolleri bu yapıda taşır; JwtRoleConverter buradan okur.
                .claim("realm_access", Map.of("roles", List.of(roles)));
    }

    private static JWSHeader header() {
        return new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();
    }

    private static String sign(JWTClaimsSet claimsSet) {
        try {
            SignedJWT jwt = new SignedJWT(header(), claimsSet);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Token imzalanamadı", e);
        }
    }
}
