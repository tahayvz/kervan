package com.kervan.order.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Zamanı bean olarak sağlar.
 * <p>
 * {@code Instant.now()} çağırmak yerine enjekte edilen {@link Clock} kullanmak,
 * testte zamanı sabitlemeyi mümkün kılar; "bugün çalışan, yarın 23:59'da patlayan"
 * testlerden kurtarır.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
