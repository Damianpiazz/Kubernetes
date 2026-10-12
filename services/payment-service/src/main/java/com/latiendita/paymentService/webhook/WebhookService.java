package com.latiendita.paymentService.webhook;

import com.latiendita.paymentService.payment.PaymentService;
import com.latiendita.paymentService.provider.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookService {

    private final WebhookSignatureValidator signatureValidator;
    private final WebhookInboxService inboxService;
    private final PaymentService paymentService;

    public void handle(ProviderType provider, String signatureHeader, String requestId,
                       WebhookNotification notification, String queryResourceId,
                       String queryType) {

        String resourceId = resolveResourceId(notification, queryResourceId);
        String resourceType = resolveResourceType(notification, queryType);
        String notificationId = resolveNotificationId(notification, resourceType, resourceId);

        if (resourceType == null || resourceId == null) {
            log.warn("Notificación de {} inválida: type={} data.id={}",
                    provider, resourceType, resourceId);
            return;
        }

        boolean signatureValid =
                signatureValidator.isValid(signatureHeader, requestId, resourceId);

        boolean shouldProcess = inboxService.record(provider, notificationId, resourceType,
                resourceId, notification == null ? null : notification.action(), signatureValid);

        if (!shouldProcess) {
            log.info("Notificación duplicada de {} ya resuelta; se omite: recurso {}",
                    provider, resourceId);
            return;
        }

        if (!signatureValid) {
            log.warn("Notificación de {} descartada por firma inválida: recurso {}",
                    provider, resourceId);
            inboxService.markIgnored(provider, notificationId, "Firma inválida");
            return;
        }

        if (!"payment".equalsIgnoreCase(resourceType)) {
            log.info("Notificación de tipo {} ignorada", resourceType);
            inboxService.markIgnored(provider, notificationId,
                    "Tipo de recurso no soportado: " + resourceType);
            return;
        }

        try {
            paymentService.applyFromProvider(provider, resourceId);
            inboxService.markProcessed(provider, notificationId);
        } catch (Exception ex) {
            log.error("Error procesando la notificación {} de {}: {}",
                    resourceId, provider, ex.getMessage(), ex);
            inboxService.markFailed(provider, notificationId, ex.getMessage());
        }
    }

    private static String resolveResourceId(WebhookNotification notification,
                                            String queryResourceId) {
        if (notification != null && notification.data() != null
                && notification.data().id() != null && !notification.data().id().isBlank()) {
            return notification.data().id();
        }
        return queryResourceId;
    }

    private static String resolveResourceType(WebhookNotification notification, String queryType) {
        if (notification != null && notification.type() != null
                && !notification.type().isBlank()) {
            return notification.type();
        }
        return queryType;
    }

    private static String resolveNotificationId(WebhookNotification notification,
                                                String resourceType, String resourceId) {
        if (notification != null && notification.id() != null) {
            return String.valueOf(notification.id());
        }
        return resourceType + ":" + resourceId;
    }
}
