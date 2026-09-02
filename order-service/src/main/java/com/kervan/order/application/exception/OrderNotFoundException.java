package com.kervan.order.application.exception;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderId) {
        super("Sipariş bulunamadı: " + orderId);
    }
}
