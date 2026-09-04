package com.kervan.order.domain.model;

import java.util.Objects;
import java.util.Set;

/**
 * İsteği yapan kimlik.
 * <p>
 * <b>Neden domain'de?</b> "Bir müşteri yalnızca kendi siparişini görebilir" bir iş
 * kuralıdır, HTTP ayrıntısı değil. Kuralı domain'de tutmak, onu bir web sunucusu
 * ayağa kaldırmadan test edilebilir kılar; ayrıca yarın bir Kafka tüketicisi ya da
 * toplu iş aynı use-case'i çağırdığında kural kendiliğinden geçerli olur.
 * <p>
 * Kimliğin nereden geldiği (JWT, oturum, servis hesabı) adaptörün sorunudur.
 */
public record Caller(String userId, Set<String> roles) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    public Caller {
        Objects.requireNonNull(userId, "userId null olamaz");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public static Caller customer(String userId) {
        return new Caller(userId, Set.of(ROLE_CUSTOMER));
    }

    public static Caller admin(String userId) {
        return new Caller(userId, Set.of(ROLE_ADMIN));
    }

    public boolean isAdmin() {
        return roles.contains(ROLE_ADMIN);
    }

    public boolean owns(Order order) {
        return userId.equals(order.customerId());
    }
}
