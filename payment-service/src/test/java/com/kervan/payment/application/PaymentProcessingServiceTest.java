package com.kervan.payment.application;

import com.kervan.payment.domain.model.Money;
import com.kervan.payment.domain.model.Payment;
import com.kervan.payment.domain.model.PaymentDeclinedException;
import com.kervan.payment.domain.model.PaymentStatus;
import com.kervan.payment.domain.port.PaymentEventPublisher;
import com.kervan.payment.domain.port.PaymentGateway;
import com.kervan.payment.domain.port.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PaymentProcessingService")
class PaymentProcessingServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");
    private static final String ORDER = "order-1";
    private static final Money AMOUNT = Money.of(new BigDecimal("100.00"), "TRY");

    private PaymentRepository payments;
    private PaymentGateway gateway;
    private PaymentEventPublisher events;
    private PaymentProcessingService service;

    @BeforeEach
    void setUp() {
        payments = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        events = mock(PaymentEventPublisher.class);
        when(payments.findByOrderId(anyString())).thenReturn(Optional.empty());
        when(payments.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            return new Payment("pay-1", p.orderId(), p.customerId(), p.amount(),
                    p.status(), p.createdAt(), p.updatedAt());
        });
        service = new PaymentProcessingService(payments, gateway, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("tahsilat başarılıysa kayıt ve olay yazılır")
    void capturesAndAnnounces() {
        service.process(ORDER, "c-1", AMOUNT);

        verify(gateway).charge(ORDER, "c-1", AMOUNT);
        verify(payments).save(any(Payment.class));
        verify(events).paymentProcessed(ORDER, "pay-1", AMOUNT, NOW);
    }

    @Test
    @DisplayName("sağlayıcı reddederse kayıt yazılmaz ama olay yazılır")
    void writesFailureEventWhenDeclined() {
        when(gateway.charge(anyString(), anyString(), any()))
                .thenThrow(new PaymentDeclinedException("limit aşıldı"));

        service.process(ORDER, "c-1", AMOUNT);

        // İstisna dışarı taşsaydı transaction geri alınır ve bu olay da silinirdi;
        // saga cevap beklerken asılı kalırdı.
        verify(payments, never()).save(any());
        verify(events).paymentFailed(eq(ORDER), anyString(), eq(NOW));
        verify(events, never()).paymentProcessed(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("aynı komut ikinci kez gelirse ikinci kez tahsilat yapılmaz")
    void ignoresDuplicateProcessCommand() {
        when(payments.findByOrderId(ORDER)).thenReturn(Optional.of(capturedPayment()));

        service.process(ORDER, "c-1", AMOUNT);

        // Müşteriden iki kez para çekmek, stok fazlası ayırmaktan daha ciddi bir hata.
        verify(gateway, never()).charge(anyString(), anyString(), any());
        verify(events, never()).paymentProcessed(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("iade tahsil edilmiş ödemeyi geri verir")
    void refundReturnsMoney() {
        when(payments.findByOrderId(ORDER)).thenReturn(Optional.of(capturedPayment()));

        service.refund(ORDER);

        verify(gateway).refund(ORDER, "pay-1", AMOUNT);
        verify(events).paymentRefunded(ORDER, "pay-1", NOW);
    }

    @Test
    @DisplayName("iade ikinci kez gelirse para iki kez gönderilmez")
    void ignoresDuplicateRefund() {
        when(payments.findByOrderId(ORDER)).thenReturn(Optional.of(
                new Payment("pay-1", ORDER, "c-1", AMOUNT, PaymentStatus.REFUNDED, NOW, NOW)));

        service.refund(ORDER);

        verify(gateway, never()).refund(anyString(), anyString(), any());
        verify(events, never()).paymentRefunded(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("hiç tahsilat yapılmamışsa iade sessizce geçer")
    void refundIsANoOpWhenNothingWasCaptured() {
        when(payments.findByOrderId(ORDER)).thenReturn(Optional.empty());

        service.refund(ORDER);

        // Sağlayıcı baştan reddetmiş olabilir; iade komutu yine de gelir.
        verify(gateway, never()).refund(anyString(), anyString(), any());
        verify(events, never()).paymentRefunded(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("iade sırasında sağlayıcı hata verirse kayıt güncellenmez")
    void doesNotMarkRefundedWhenGatewayFails() {
        when(payments.findByOrderId(ORDER)).thenReturn(Optional.of(capturedPayment()));
        doThrow(new IllegalStateException("sağlayıcı erişilemiyor"))
                .when(gateway).refund(anyString(), anyString(), any());

        try {
            service.refund(ORDER);
        } catch (IllegalStateException expected) {
            // Beklenen: dışarı taşar, transaction geri alınır, komut tekrar gelir.
        }

        // Kayıt "iade edildi" olarak işaretlenmemeli; aksi hâlde para gönderilmemiş
        // olduğu hâlde iade yapılmış sayılır ve tekrar denenmez.
        verify(events, never()).paymentRefunded(anyString(), anyString(), any());
    }

    private Payment capturedPayment() {
        return new Payment("pay-1", ORDER, "c-1", AMOUNT, PaymentStatus.CAPTURED, NOW, NOW);
    }
}
