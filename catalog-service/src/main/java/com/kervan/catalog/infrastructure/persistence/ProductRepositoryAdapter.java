package com.kervan.catalog.infrastructure.persistence;

import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.port.PageResult;
import com.kervan.catalog.domain.port.ProductQuery;
import com.kervan.catalog.domain.port.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * {@link ProductRepository} portunun MongoDB gerçeklemesi (hexagonal adaptör).
 * <p>
 * CRUD için Spring Data deposunu; esnek/opsiyonel filtreli listeleme için
 * {@link MongoTemplate} + {@code Criteria} kullanır. Domain nesneleriyle konuşur,
 * {@link ProductDocument}'ı dışarı sızdırmaz.
 */
@Component
public class ProductRepositoryAdapter implements ProductRepository {

    private final SpringDataProductRepository repository;
    private final MongoTemplate mongoTemplate;
    private final ProductDocumentMapper mapper;

    public ProductRepositoryAdapter(SpringDataProductRepository repository,
                                    MongoTemplate mongoTemplate,
                                    ProductDocumentMapper mapper) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.mapper = mapper;
    }

    @Override
    public Product save(Product product) {
        ProductDocument saved = repository.save(mapper.toDocument(product));
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Product> findById(String id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public boolean existsBySku(String sku) {
        return repository.existsBySku(sku);
    }

    @Override
    public PageResult<Product> search(ProductQuery query) {
        Query mongoQuery = new Query();
        if (query.categoryPath() != null && !query.categoryPath().isBlank()) {
            mongoQuery.addCriteria(Criteria.where("categoryPath").is(query.categoryPath()));
        }
        if (query.status() != null) {
            mongoQuery.addCriteria(Criteria.where("status").is(query.status()));
        }

        // Toplam sayı filtrelerle hesaplanır (sayfalama uygulanmadan)
        long total = mongoTemplate.count(mongoQuery, ProductDocument.class);

        // Sonra sayfalama + sıralama uygulanır
        mongoQuery.with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) query.page() * query.size())
                .limit(query.size());

        List<Product> items = mongoTemplate.find(mongoQuery, ProductDocument.class)
                .stream()
                .map(mapper::toDomain)
                .toList();

        return new PageResult<>(items, query.page(), query.size(), total);
    }

    @Override
    public void deleteById(String id) {
        repository.deleteById(id);
    }
}
