package com.latiendita.paymentService.webhook;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.latiendita.paymentService.exception.PaymentGatewayUnavailableException;
import com.latiendita.paymentService.payment.PaymentService;
import com.latiendita.paymentService.provider.ProviderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    private static final String SIGNATURE = "ts=1704908010,v1=abcdef";
    private static final String REQUEST_ID = "req-1";
    private static final String RESOURCE_ID = "1234567890";

    @Mock
    private WebhookSignatureValidator signatureValidator;

    @Mock
    private WebhookInboxService inboxService;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private WebhookService webhookService;

    private WebhookNotification notification;

    @BeforeEach
    void setUp() {
        notification = new WebhookNotification(99L, "payment.updated", "payment",
                new WebhookNotification.Data(RESOURCE_ID));
    }

    @Test
    void shouldProcessValidPaymentNotification() {
        when(signatureValidator.isValid(SIGNATURE, REQUEST_ID, RESOURCE_ID)).thenReturn(true);
        when(inboxService.record(eq(ProviderType.MERCADO_PAGO), eq("99"), eq("payment"),
                eq(RESOURCE_ID), eq("payment.updated"), eq(true))).thenReturn(true);

        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                notification, null, null);

        verify(paymentService).applyFromProvider(ProviderType.MERCADO_PAGO, RESOURCE_ID);
        verify(inboxService).markProcessed(ProviderType.MERCADO_PAGO, "99");
    }

    @Test
    void shouldIgnoreNotificationWithInvalidSignature() {
        when(signatureValidator.isValid(SIGNATURE, REQUEST_ID, RESOURCE_ID)).thenReturn(false);
        when(inboxService.record(any(ProviderType.class), anyString(), anyString(),
                anyString(), any(), anyBoolean())).thenReturn(true);

        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                notification, null, null);

        verify(paymentService, never()).applyFromProvider(any(), anyString());
        verify(inboxService).markIgnored(eq(ProviderType.MERCADO_PAGO), eq("99"),
                anyString());
    }

    @Test
    void shouldIgnoreDuplicatedNotification() {
        when(signatureValidator.isValid(SIGNATURE, REQUEST_ID, RESOURCE_ID)).thenReturn(true);
        when(inboxService.record(any(ProviderType.class), anyString(), anyString(), anyString(),
                any(), anyBoolean())).thenReturn(false);

        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                notification, null, null);

        verify(paymentService, never()).applyFromProvider(any(), anyString());
    }

    @Test
    void shouldIgnoreNonPaymentResourceType() {
        WebhookNotification merchantOrder = new WebhookNotification(100L, "merchant_order",
                "merchant_order", new WebhookNotification.Data(RESOURCE_ID));
        when(signatureValidator.isValid(SIGNATURE, REQUEST_ID, RESOURCE_ID)).thenReturn(true);
        when(inboxService.record(any(ProviderType.class), anyString(), anyString(), anyString(),
                any(), anyBoolean())).thenReturn(true);

        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                merchantOrder, null, null);

        verify(paymentService, never()).applyFromProvider(any(), anyString());
        verify(inboxService).markIgnored(eq(ProviderType.MERCADO_PAGO), eq("100"),
                anyString());
    }

    @Test
    void shouldFallBackToQueryParamsWhenBodyIsAbsent() {
        when(signatureValidator.isValid(SIGNATURE, REQUEST_ID, RESOURCE_ID)).thenReturn(true);
        when(inboxService.record(eq(ProviderType.MERCADO_PAGO), eq("payment:" + RESOURCE_ID),
                eq("payment"), eq(RESOURCE_ID), eq(null), eq(true))).thenReturn(true);

        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                null, RESOURCE_ID, "payment");

        verify(paymentService).applyFromProvider(ProviderType.MERCADO_PAGO, RESOURCE_ID);
    }

    @Test
    void shouldReturnEarlyWhenThereIsNoResourceAtAll() {
        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                null, null, null);

        verify(signatureValidator, never()).isValid(any(), any(), any());
        verify(paymentService, never()).applyFromProvider(any(), anyString());
    }

    @Test
    void shouldRecordFailureAndSwallowExceptionSoTheEndpointStillReturns200() {
        when(signatureValidator.isValid(SIGNATURE, REQUEST_ID, RESOURCE_ID)).thenReturn(true);
        when(inboxService.record(any(ProviderType.class), anyString(), anyString(), anyString(),
                any(), anyBoolean())).thenReturn(true);
        doThrow(new PaymentGatewayUnavailableException("MP caído"))
                .when(paymentService).applyFromProvider(any(), anyString());

        webhookService.handle(ProviderType.MERCADO_PAGO, SIGNATURE, REQUEST_ID,
                notification, null, null);

        verify(inboxService).markFailed(eq(ProviderType.MERCADO_PAGO), eq("99"), anyString());
        verify(inboxService, never()).markProcessed(any(), anyString());
    }
}
