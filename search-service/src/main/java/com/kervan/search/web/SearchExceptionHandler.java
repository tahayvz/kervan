package com.kervan.search.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Hatalar RFC 7807 biçiminde döner — diğer servislerle aynı.
 */
@RestControllerAdvice
class SearchExceptionHandler {

    /**
     * Geçersiz sorgu parametreleri (sayfa boyutu sınırı, ters fiyat aralığı) istemci
     * hatasıdır: 400 döner, 500 değil. Aksi hâlde istemcinin düzeltebileceği bir
     * durum sunucu arızası gibi görünür ve alarm üretirdi.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidQuery(IllegalArgumentException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Geçersiz arama sorgusu");
        return problem;
    }
}
