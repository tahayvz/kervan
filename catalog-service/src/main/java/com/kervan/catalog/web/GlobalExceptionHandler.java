package com.kervan.catalog.web;

import com.kervan.catalog.application.exception.DuplicateSkuException;
import com.kervan.catalog.application.exception.ProductNotFoundException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Merkezî hata yönetimi — tüm hatalar tek tip, makine-okur formatta döner:
 * <b>RFC 7807 Problem Details</b> ({@code application/problem+json}).
 * <p>
 * Neden RFC 7807? Kurumsal API'lerde hata gövdesi standart olmalı ki tüketiciler
 * (Gateway, frontend, diğer servisler) hataları tutarlı işleyebilsin. Her controller'da
 * try-catch tekrarı yerine tek yerde, kesitsel (cross-cutting) çözüm.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ProblemDetail handleNotFound(ProductNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Ürün bulunamadı", ex.getMessage(), "product-not-found");
    }

    /**
     * SKU çakışması. İki kaynaktan gelebilir:
     * (1) uygulama kontrolü {@link DuplicateSkuException},
     * (2) yarış durumunda MongoDB benzersiz indeks ihlali {@link DuplicateKeyException}.
     * İkisi de aynı 409 yanıtına indirgenir.
     */
    @ExceptionHandler({DuplicateSkuException.class, DuplicateKeyException.class})
    public ProblemDetail handleDuplicate(Exception ex) {
        return problem(HttpStatus.CONFLICT, "Çakışma", ex.getMessage(), "duplicate-sku");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Eşzamanlı güncelleme çakışması",
                "Kayıt başka bir işlem tarafından değiştirildi, lütfen tekrar deneyin.",
                "optimistic-lock");
    }

    /** Bean Validation hataları (@Valid) — alan bazlı ayrıntı ile 400. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Doğrulama hatası",
                "İstek gövdesi geçersiz.", "validation-error");
        pd.setProperty("errors", errors);
        return pd;
    }

    /** Domain seviyesinde atılan kural ihlalleri (örn. negatif fiyat, geçersiz para birimi). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Geçersiz istek", ex.getMessage(), "invalid-argument");
    }

    /** Beklenmeyen hatalar — 500. İç detay sızdırılmaz; log'a düşer. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Sunucu hatası",
                "Beklenmeyen bir hata oluştu.", "internal-error");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://kervan.com/errors/" + code));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
