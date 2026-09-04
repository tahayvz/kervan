package com.kervan.gateway;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

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
 * <p>order-service'teki eşdeğerinin reaktif hâli: ağ geçidi WebFlux üzerinde
 * çalıştığı için {@link ReactiveJwtDecoder} gerekir.
 *
 * <p><b>Neden Keycloak ayağa kaldırılmıyor?</b> Doğrulanmak istenen şey Keycloak'ın
 * çalıştığı değil, <em>ağ geçidinin</em> geçerli imzayı kabul edip geçersizi
 * reddettiğidir. Anahtar çifti test çalışırken üretilir; repoya özel anahtar girmez
 * ve testler ağa bağımlı olmaz.
 *
 * <p>Bean burada tanımlandığı için Boot'un otomatik yapılandırması devreye girmez
 * ve {@code issuer-uri} adresine istek atılmaz.
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
    ReactiveJwtDecoder testJwtDecoder() {
        return NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
    }

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
