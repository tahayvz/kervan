package com.kervan.catalog.web;

import com.kervan.catalog.application.ProductService;
import com.kervan.catalog.application.command.CreateProductCommand;
import com.kervan.catalog.application.command.UpdateProductCommand;
import com.kervan.catalog.domain.model.Money;
import com.kervan.catalog.domain.model.Product;
import com.kervan.catalog.domain.model.ProductStatus;
import com.kervan.catalog.domain.port.PageResult;
import com.kervan.catalog.domain.port.ProductQuery;
import com.kervan.catalog.web.dto.CreateProductRequest;
import com.kervan.catalog.web.dto.PageResponse;
import com.kervan.catalog.web.dto.ProductResponse;
import com.kervan.catalog.web.dto.UpdateProductRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.Map;

/**
 * Ürün REST API'si. İnce bir katman: HTTP ↔ application çevirisi yapar, iş kuralı içermez.
 * DTO doğrulaması {@code @Valid} ile; DTO → komut çevirisi burada; komut → use-case
 * {@link ProductService}'te.
 */
@RestController
@RequestMapping("/api/v1/products")
@Tag(name = "Products", description = "Ürün kataloğu yönetimi")
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Yeni ürün oluştur (DRAFT durumunda)")
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody CreateProductRequest req,
                                                  UriComponentsBuilder uriBuilder) {
        Product created = service.create(new CreateProductCommand(
                req.sku(), req.name(), req.description(), req.brand(), req.categoryPath(),
                toMoney(req.price().amount(), req.price().currency()),
                req.attributes() == null ? Map.of() : req.attributes()));

        URI location = uriBuilder.path("/api/v1/products/{id}")
                .buildAndExpand(created.getId()).toUri();
        return ResponseEntity.created(location).body(ProductResponse.from(created));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Ürünü id ile getir")
    public ProductResponse get(@PathVariable String id) {
        return ProductResponse.from(service.get(id));
    }

    @GetMapping
    @Operation(summary = "Ürünleri listele (kategori/durum filtresi + sayfalama)")
    public PageResponse list(@RequestParam(required = false) String category,
                             @RequestParam(required = false) ProductStatus status,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "20") int size) {
        PageResult<Product> result = service.search(new ProductQuery(category, status, page, size));
        return PageResponse.from(result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Ürün detaylarını güncelle")
    public ProductResponse update(@PathVariable String id,
                                  @Valid @RequestBody UpdateProductRequest req) {
        Product updated = service.update(id, new UpdateProductCommand(
                req.name(), req.description(), req.categoryPath(),
                toMoney(req.price().amount(), req.price().currency()),
                req.attributes() == null ? Map.of() : req.attributes()));
        return ProductResponse.from(updated);
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Ürünü yayına al (ACTIVE)")
    public ProductResponse activate(@PathVariable String id) {
        return ProductResponse.from(service.activate(id));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Ürünü arşivle (ARCHIVED)")
    public ProductResponse archive(@PathVariable String id) {
        return ProductResponse.from(service.archive(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Ürünü kalıcı sil")
    public void delete(@PathVariable String id) {
        service.delete(id);
    }

    private static Money toMoney(java.math.BigDecimal amount, String currency) {
        return Money.of(amount, currency);
    }
}
