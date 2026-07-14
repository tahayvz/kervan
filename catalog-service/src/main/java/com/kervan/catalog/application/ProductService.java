package com.kervan.catalog.application;

import com.kervan.catalog.application.command.CreateProductCommand;
import com.kervan.catalog.application.command.UpdateProductCommand;
import com.kervan.catalog.application.exception.DuplicateSkuException;
import com.kervan.catalog.application.exception.ProductNotFoundException;
import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.port.PageResult;
import com.kervan.catalog.domain.port.ProductQuery;
import com.kervan.catalog.domain.port.ProductRepository;
import org.springframework.stereotype.Service;

/**
 * Katalog use-case'lerini orkestre eden application servisi.
 * <p>
 * Sadece <b>port</b>'a ({@link ProductRepository}) bağımlıdır — MongoDB'yi bilmez.
 * İş kuralları {@link Product} domain nesnesinin içindedir; buradaki metotlar akışı
 * (bul → kural uygula → kaydet) yönetir. İnce ve okunur tutulur.
 */
@Service
public class ProductService {

    private final ProductRepository repository;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
    }

    public Product create(CreateProductCommand cmd) {
        // 1. savunma hattı: uygulama seviyesinde SKU tekilliği (dostça 409 mesajı için)
        if (repository.existsBySku(cmd.sku())) {
            throw new DuplicateSkuException(cmd.sku());
        }
        Product product = Product.create(
                cmd.sku(), cmd.name(), cmd.description(), cmd.brand(),
                cmd.categoryPath(), cmd.price(), cmd.attributes());
        return repository.save(product);
    }

    public Product get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    public Product update(String id, UpdateProductCommand cmd) {
        Product product = get(id);
        product.updateDetails(cmd.name(), cmd.description(), cmd.categoryPath(), cmd.attributes());
        product.changePrice(cmd.price());
        return repository.save(product);
    }

    public Product activate(String id) {
        Product product = get(id);
        product.activate();
        return repository.save(product);
    }

    public Product archive(String id) {
        Product product = get(id);
        product.archive();
        return repository.save(product);
    }

    public void delete(String id) {
        if (repository.findById(id).isEmpty()) {
            throw new ProductNotFoundException(id);
        }
        repository.deleteById(id);
    }

    public PageResult<Product> search(ProductQuery query) {
        return repository.search(query);
    }
}
