package com.kervan.inventory.web;

import com.kervan.inventory.domain.model.StockNotFoundException;
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
 * Merkezî hata yönetimi — hatalar tek tip, makine-okur formatta döner:
 * <b>RFC 7807 Problem Details</b>. catalog-service ve order-service ile aynı desen.
 *
 * <h2>Burada KASITLI olarak eksik olan şey</h2>
 * {@code @ExceptionHandler(Exception.class)} gibi bir yakala-hepsini <b>yok</b>.
 *
 * <p>Böyle bir işleyici, kendi sınıfını daraltmadığı sürece Spring Security'nin
 * {@code AccessDeniedException}'ını da yutabilir ve 403 olması gereken cevabı 500'e
 * çevirir. Bu, güvenlik davranışını sessizce bozan türden bir hatadır: testler
 * "hata döndü" diye yeşil kalır, oysa artık "yetkin yok" demiyoruz, "sunucum bozuk"
 * diyoruz.
 *
 * <p>Bu serviste yetki denetimi filtre zincirinde yapıldığı için (SecurityConfig)
 * istisna zaten DispatcherServlet'e ulaşmaz; yani bugün tehlike yok. Yakala-hepsini
 * yine de eklenmedi: bugün doğru olan bir şeyin yarın metot seviyesinde bir
 * {@code @PreAuthorize} eklendiğinde sessizce yanlış olması, tam da kaçınılması gereken
 * şey. Ele alınmayan istisnalar Spring Boot'un varsayılan 500'üne düşer ve log'a yazılır.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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

    /**
     * Düzeltme, stok kaydı hiç açılmamış bir SKU için istendi.
     *
     * <p>Mal kabulünde bu durum hata değildir (kabul kaydı kendisi açar); düzeltmede
     * hatadır, çünkü var olmayan bir sayı düzeltilemez.
     */
    @ExceptionHandler(StockNotFoundException.class)
    public ProblemDetail handleStockNotFound(StockNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Stok kaydı yok", ex.getMessage(), "stock-not-found");
    }

    /** Alan modelinin kural ihlalleri (örn. miktar pozitif değil). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Geçersiz istek", ex.getMessage(), "invalid-argument");
    }

    /**
     * Stok taşması ({@code Math.addExact}).
     *
     * <p>400 değil <b>409</b>: istek kendi başına geçerli, kabul edilemez olan şey
     * sistemin mevcut durumu. İstemcinin göndereceği farklı bir miktar sorunu çözmez;
     * çözüm stoğun düzeltilmesidir.
     */
    @ExceptionHandler(ArithmeticException.class)
    public ProblemDetail handleOverflow(ArithmeticException ex) {
        return problem(HttpStatus.CONFLICT, "Stok taşması",
                "Bu giriş stoğu sayılabilir sınırın dışına taşırırdı.", "stock-overflow");
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://kervan.com/errors/" + code));
        pd.setProperty("timestamp", Instant.now());
        return pd;
    }
}
