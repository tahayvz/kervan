package com.kervan.inventory.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Zaman bir bağımlılıktır.
 *
 * <p>{@code Instant.now()} çağıran kod test edilemez: "5 dakika sonra" gibi bir durumu
 * kurmanın yolu yoktur. Saati enjekte etmek, testte sabitlemeyi mümkün kılar.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
