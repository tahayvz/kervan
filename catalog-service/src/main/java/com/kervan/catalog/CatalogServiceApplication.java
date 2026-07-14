package com.kervan.catalog;

import io.mongock.runner.springboot.EnableMongock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Catalog Service giriş noktası.
 * <p>
 * {@code @EnableMongock} — uygulama başlarken MongoDB migration'larını (indeksler)
 * çalıştırır. Böylece koleksiyon şeması/indeksleri her ortamda deterministik kurulur.
 */
@SpringBootApplication
@EnableMongock
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
