package com.kervan.catalog.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Önbellek yapılandırması.
 *
 * <h2>Neden JSON, Java serileştirmesi değil?</h2>
 * Varsayılan {@code JdkSerializationRedisSerializer} sınıfın ikili biçimini saklar:
 * sınıfa alan eklendiğinde eski kayıtlar okunamaz hâle gelir ve dağıtım sırasında
 * eski/yeni sürüm yan yana çalışırken patlar. JSON okunabilir ve alan eklemeye
 * dayanıklıdır — Redis'e bakıp ne saklandığını görebilmek de ayrı bir fayda.
 *
 * <h2>Neden TTL var?</h2>
 * Yazma yolunda önbellek zaten temizleniyor, yani TTL doğruluk için değil <b>emniyet
 * için</b>. Bir temizleme kaçarsa (Redis o an erişilemezse) bayat kayıt sonsuza kadar
 * kalmaz; en fazla bu süre kadar yaşar. Süresiz bir önbellek, tek bir kaçan
 * temizlemeyi kalıcı hataya çevirir.
 */
@Configuration
class RedisCacheConfig {

    /** Ürün önbelleğinin adı; {@link CachingProductRepository} bunu kullanır. */
    static final String PRODUCTS_CACHE = "products";

    @Bean
    RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                   @Value("${kervan.cache.product-ttl}") Duration productTtl) {

        ObjectMapper mapper = new ObjectMapper()
                // Instant alanları var; modül olmadan sayıya çevrilir ve geri
                // okunduğunda tip bilgisi kaybolur.
                .registerModule(new JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisCacheConfiguration configuration = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(productTtl)
                // null saklamak, "bu ürün yok" bilgisini de önbelleğe almak olurdu;
                // ürün eklendiğinde o bilgi bayatlar. Yokluk önbelleklenmez.
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new Jackson2JsonRedisSerializer<>(mapper, CachedProduct.class)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(configuration)
                .build();
    }
}
