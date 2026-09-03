package com.kervan.order.web;

import com.kervan.order.application.exception.OrderNotFoundException;
import com.kervan.order.domain.model.InvalidStatusTransitionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hata yanıtları RFC 7807 {@code application/problem+json} biçimindedir.
 * <p>
 * Kendi JSON şeklimizi uydurmak yerine standardı kullanmak, istemcilerin hataları
 * tek bir sözleşmeye göre işlemesini sağlar.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Sipariş bulunamadı");
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "İstek gövdesi doğrulanamadı");
        problem.setTitle("Doğrulama hatası");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Domain kuralı ihlalleri ({@code Order.place}, {@code Money}) burada 400'e çevrilir.
     * Bunlar programlama hatası değil, geçersiz istek sonucudur.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidArgument(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Geçersiz istek");
        return problem;
    }

    /**
     * Yalnızca gerçek durum çakışmaları 409 döner.
     * <p>
     * Daha önce burada {@code IllegalStateException} yakalanıyordu; bu, sunucu tarafı
     * hatalarını da (örneğin olay serialize edilemediğinde) istemciye "çakışma" diye
     * bildiriyordu. Öyle bir yanıt alan istemci tekrar denemez ve 5xx üzerine kurulu
     * alarmlar sessiz kalırdı. Artık o tür hatalar Spring'in varsayılan 500 yoluna
     * düşüyor.
     */
    @ExceptionHandler(InvalidStatusTransitionException.class)
    ProblemDetail handleInvalidTransition(InvalidStatusTransitionException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("İşlem mevcut durumda yapılamaz");
        return problem;
    }
}
