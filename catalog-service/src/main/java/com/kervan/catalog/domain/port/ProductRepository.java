package com.kervan.catalog.domain.port;

import com.kervan.catalog.domain.model.Product;

import java.util.Optional;

/**
 * Ürün kalıcılık portu (hexagonal "driven port").
 * <p>
 * Bu arayüz <b>domain</b> katmanında tanımlıdır ama <b>infrastructure</b> katmanında
 * (MongoDB adaptörü) gerçeklenir. Böylece application/domain, MongoDB'yi hiç bilmez;
 * yarın depo değişse (örn. başka bir document store) domain kodu değişmez.
 */
public interface ProductRepository {

    Product save(Product product);

    Optional<Product> findById(String id);

    boolean existsBySku(String sku);

    PageResult<Product> search(ProductQuery query);

    void deleteById(String id);
}
