package com.kervan.order.application.exception;

/**
 * Sipariş var, ancak çağıran onu görme yetkisine sahip değil.
 * <p>
 * Bu ayrım bilinçli: "bulunamadı" ile "erişemezsin" farklı yanıtlardır ve bu servis
 * ikincisini açıkça söyler. Bazı sistemler sızıntıyı önlemek için ikisini de 404 döner;
 * burada kimlik zaten doğrulanmış olduğu için kimliğin doğru ama yetkinin yetersiz
 * olduğunu bildirmek daha faydalıdır.
 */
public class OrderAccessDeniedException extends RuntimeException {

    public OrderAccessDeniedException(String orderId) {
        super("Bu siparişe erişim yetkiniz yok: " + orderId);
    }
}
