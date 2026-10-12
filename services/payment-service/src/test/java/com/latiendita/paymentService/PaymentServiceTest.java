package com.latiendita.paymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.paymentService.exception.DuplicatePaymentException;
import com.latiendita.paymentService.exception.InvalidPaymentException;
import com.latiendita.paymentService.exception.OrderNotFoundException;
import com.latiendita.paymentService.exception.PaymentAccessDeniedException;
import com.latiendita.paymentService.exception.PaymentGatewayUnavailableException;
import com.latiendita.paymentService.exception.PaymentNotFoundException;
import com.latiendita.paymentService.order.OrderClient;
import com.latiendita.paymentService.order.OrderDto;
import com.latiendita.paymentService.outbox.OutboxWriter;
import com.latiendita.paymentService.payment.PaymentEntity;
import com.latiendita.paymentService.payment.PaymentRepository;
import com.latiendita.paymentService.payment.PaymentService;
import com.latiendita.paymentService.payment.PaymentStatus;
import com.latiendita.paymentService.payment.dto.CreatePaymentRequest;
import com.latiendita.paymentService.payment.dto.PaymentResponse;
import com.latiendita.paymentService.payment.dto.event.PaymentApprovedEvent;
import com.latiendita.paymentService.payment.dto.event.PaymentCreatedEvent;
import com.latiendita.paymentService.payment.dto.event.PaymentRejectedEvent;
import com.latiendita.paymentService.provider.PaymentGateway;
import com.latiendita.paymentService.provider.PaymentGatewayFactory;
import com.latiendita.paymentService.provider.ProviderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final Long CUSTOMER = 1L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private OrderClient orderClient;

    @Mock
    private PaymentGatewayFactory gatewayFactory;

    @Mock
    private PaymentGateway gateway;

    @InjectMocks
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        setCurrentUserHeader(String.valueOf(CUSTOMER));
        ReflectionTestUtils.setField(paymentService, "provider", ProviderType.MERCADO_PAGO);
        ReflectionTestUtils.setField(paymentService, "defaultCurrency", "ARS");
        ReflectionTestUtils.setField(paymentService, "notificationUrl",
                "https://api.tienda.com/api/webhooks/mercado-pago");
        ReflectionTestUtils.setField(paymentService, "backUrlSuccess",
                "https://tienda.com/checkout/success");
        ReflectionTestUtils.setField(paymentService, "backUrlFailure",
                "https://tienda.com/checkout/failure");
        ReflectionTestUtils.setField(paymentService, "backUrlPending",
                "https://tienda.com/checkout/pending");
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldCreatePaymentWithPreferenceAndPublishCreatedEvent() {
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(orderClient.findById(7L)).thenReturn(pendingOrder());
        when(gateway.createPreference(any())).thenReturn(
                new PaymentGateway.GatewayPreference("pref-1", "https://mp/checkout?pref_id=pref-1"));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation -> {
            PaymentEntity payment = invocation.getArgument(0);
            payment.setId(1L);
            return payment;
        });

        PaymentResponse response = paymentService.create(new CreatePaymentRequest(7L, "ana@mail.com"));

        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.orderId()).isEqualTo(7L);
        assertThat(response.orderNumber()).isEqualTo("ORD-7");
        assertThat(response.customerId()).isEqualTo(CUSTOMER);
        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.amount()).isEqualByComparingTo("2049.48");
        assertThat(response.currency()).isEqualTo("ARS");
        assertThat(response.preferenceId()).isEqualTo("pref-1");
        assertThat(response.checkoutUrl()).isEqualTo("https://mp/checkout?pref_id=pref-1");

        ArgumentCaptor<PaymentGateway.GatewayPreferenceRequest> preference =
                ArgumentCaptor.forClass(PaymentGateway.GatewayPreferenceRequest.class);
        verify(gateway).createPreference(preference.capture());
        assertThat(preference.getValue().externalReference()).isEqualTo("ORD-7");
        assertThat(preference.getValue().payerEmail()).isEqualTo("ana@mail.com");
        assertThat(preference.getValue().items()).hasSize(2);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(PaymentService.PAYMENT_EVENTS_TOPIC), eq(7L),
                eq("PAYMENT_CREATED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(PaymentCreatedEvent.class);
    }

    @Test
    void shouldRejectPaymentForOrderThatIsAlreadyPaid() {
        when(orderClient.findById(7L)).thenReturn(order("PAID"));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7L, "ana@mail.com")))
                .isInstanceOf(DuplicatePaymentException.class);

        verify(gatewayFactory, never()).forProvider(any());
        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldRejectPaymentForOrderThatIsNotPending() {
        when(orderClient.findById(7L)).thenReturn(order("SHIPPED"));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7L, "ana@mail.com")))
                .isInstanceOf(InvalidPaymentException.class);

        verify(gatewayFactory, never()).forProvider(any());
    }

    @Test
    void shouldRejectPaymentForOrderOfAnotherCustomer() {
        when(orderClient.findById(7L)).thenReturn(orderForCustomer(999L));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7L, "ana@mail.com")))
                .isInstanceOf(OrderNotFoundException.class);

        verify(gatewayFactory, never()).forProvider(any());
    }

    @Test
    void shouldPropagateGatewayFailureAsServiceUnavailable() {
        when(orderClient.findById(7L)).thenReturn(pendingOrder());
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.createPreference(any()))
                .thenThrow(new PaymentGatewayUnavailableException("El proveedor de pagos respondió 500"));

        assertThatThrownBy(() -> paymentService.create(new CreatePaymentRequest(7L, "ana@mail.com")))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldApplyApprovedStatusFromProviderAndPublishEvent() {
        PaymentEntity payment = payment(PaymentStatus.PENDING);
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.getPayment("12345")).thenReturn(approvedGatewayPayment("2049.48"));
        when(paymentRepository.findByProviderAndProviderPaymentId(
                ProviderType.MERCADO_PAGO, "12345")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        paymentService.applyFromProvider(ProviderType.MERCADO_PAGO, "12345");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getProviderPaymentId()).isEqualTo("12345");
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getPaymentMethodId()).isEqualTo("visa");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(PaymentService.PAYMENT_EVENTS_TOPIC), eq(7L),
                eq("PAYMENT_APPROVED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(PaymentApprovedEvent.class);
    }

    @Test
    void shouldPublishRejectedEventWhenProviderRejects() {
        PaymentEntity payment = payment(PaymentStatus.PENDING);
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.getPayment("12345")).thenReturn(
                new PaymentGateway.GatewayPayment("12345", PaymentStatus.REJECTED,
                        "cc_rejected_insufficient_amount", "visa", "credit_card",
                        new BigDecimal("2049.48"), "ARS", "ORD-7"));
        when(paymentRepository.findByProviderAndProviderPaymentId(
                ProviderType.MERCADO_PAGO, "12345")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(PaymentEntity.class))).thenAnswer(invocation ->
                invocation.getArgument(0));

        paymentService.applyFromProvider(ProviderType.MERCADO_PAGO, "12345");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThat(payment.getStatusDetail()).isEqualTo("cc_rejected_insufficient_amount");
        assertThat(payment.getPaidAt()).isNull();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).append(eq(PaymentService.PAYMENT_EVENTS_TOPIC), eq(7L),
                eq("PAYMENT_REJECTED"), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(PaymentRejectedEvent.class);
    }

    @Test
    void shouldIgnoreStaleNotificationAfterApproval() {
        PaymentEntity payment = payment(PaymentStatus.APPROVED);
        payment.setProviderPaymentId("12345");
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.getPayment("12345")).thenReturn(
                new PaymentGateway.GatewayPayment("12345", PaymentStatus.PENDING,
                        null, null, null, new BigDecimal("2049.48"), "ARS", "ORD-7"));
        when(paymentRepository.findByProviderAndProviderPaymentId(
                ProviderType.MERCADO_PAGO, "12345")).thenReturn(Optional.of(payment));

        paymentService.applyFromProvider(ProviderType.MERCADO_PAGO, "12345");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        verify(paymentRepository, never()).save(any(PaymentEntity.class));
        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldRejectAmountMismatchInformedByProvider() {
        PaymentEntity payment = payment(PaymentStatus.PENDING);
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.getPayment("12345")).thenReturn(approvedGatewayPayment("1.00"));
        when(paymentRepository.findByProviderAndProviderPaymentId(
                ProviderType.MERCADO_PAGO, "12345")).thenReturn(Optional.of(payment));

        assertThatThrownBy(() ->
                paymentService.applyFromProvider(ProviderType.MERCADO_PAGO, "12345"))
                .isInstanceOf(InvalidPaymentException.class);

        verify(outboxWriter, never()).append(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void shouldThrowPaymentNotFoundForUnknownProviderResource() {
        when(gatewayFactory.forProvider(ProviderType.MERCADO_PAGO)).thenReturn(gateway);
        when(gateway.getPayment("99999")).thenReturn(approvedGatewayPayment("2049.48"));
        when(paymentRepository.findByProviderAndProviderPaymentId(
                ProviderType.MERCADO_PAGO, "99999")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                paymentService.applyFromProvider(ProviderType.MERCADO_PAGO, "99999"))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void shouldThrowPaymentNotFoundOnFindById() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.findById(99L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void shouldDenyAccessToPaymentOfAnotherCustomer() {
        PaymentEntity payment = payment(PaymentStatus.PENDING);
        payment.setCustomerId(999L);
        when(paymentRepository.findById(5L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.findById(5L))
                .isInstanceOf(PaymentAccessDeniedException.class);
    }

    @Test
    void shouldReturnPaymentByOrderIdForItsOwner() {
        PaymentEntity payment = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByOrderId(7L)).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.findByOrderId(7L);

        assertThat(response.orderId()).isEqualTo(7L);
        assertThat(response.customerId()).isEqualTo(CUSTOMER);
    }

    @Test
    void shouldDenyFindByOrderIdOfAnotherCustomer() {
        PaymentEntity payment = payment(PaymentStatus.PENDING);
        payment.setCustomerId(999L);
        when(paymentRepository.findByOrderId(7L)).thenReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentService.findByOrderId(7L))
                .isInstanceOf(PaymentAccessDeniedException.class);
    }

    private void setCurrentUserHeader(String customerId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", customerId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private OrderDto pendingOrder() {
        return order("PENDING");
    }

    private OrderDto order(String status) {
        return orderForCustomer(CUSTOMER, status);
    }

    private OrderDto orderForCustomer(Long customerId) {
        return orderForCustomer(customerId, "PENDING");
    }

    private OrderDto orderForCustomer(Long customerId, String status) {
        return new OrderDto(7L, "ORD-7", customerId, status, new BigDecimal("2049.48"),
                List.of(new OrderDto.Item(42L, "Notebook Lenovo", "NB-LEN-001", 2,
                                new BigDecimal("999.99"), new BigDecimal("1999.98")),
                        new OrderDto.Item(7L, "Mouse", "MS-001", 1,
                                new BigDecimal("49.50"), new BigDecimal("49.50"))));
    }

    private PaymentEntity payment(PaymentStatus status) {
        return PaymentEntity.builder()
                .id(1L)
                .orderId(7L)
                .customerId(CUSTOMER)
                .provider(ProviderType.MERCADO_PAGO)
                .status(status)
                .amount(new BigDecimal("2049.48"))
                .currency("ARS")
                .externalReference("ORD-7")
                .idempotencyKey("ORD-7")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private PaymentGateway.GatewayPayment approvedGatewayPayment(String amount) {
        return new PaymentGateway.GatewayPayment("12345", PaymentStatus.APPROVED,
                "accredited", "visa", "credit_card", new BigDecimal(amount), "ARS", "ORD-7");
    }
}
