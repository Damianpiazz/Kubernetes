package com.latiendita.paymentService.webhook;

import com.latiendita.paymentService.provider.ProviderType;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookInboxService {

    private final PaymentEventRepository repository;

    @Transactional
    public boolean record(ProviderType provider, String notificationId, String resourceType,
                          String resourceId, String action, boolean signatureValid) {
        Optional<PaymentEventEntity> existing =
                repository.findByProviderAndNotificationId(provider, notificationId);
        if (existing.isPresent()) {
            PaymentEventStatus status = existing.get().getStatus();
            if (status == PaymentEventStatus.PROCESSED || status == PaymentEventStatus.IGNORED) {
                log.info("Notificación duplicada ignorada: provider={} notificationId={} status={}",
                        provider, notificationId, status);
                return false;
            }
            log.info("Notificación duplicada con intento previo no resuelto; se reintenta: "
                    + "provider={} notificationId={} status={}", provider, notificationId, status);
            return true;
        }
        repository.save(PaymentEventEntity.received(
                provider, notificationId, resourceType, resourceId, action, signatureValid));
        return true;
    }

    @Transactional
    public void markProcessed(ProviderType provider, String notificationId) {
        find(provider, notificationId).ifPresent(PaymentEventEntity::markProcessed);
    }

    @Transactional
    public void markIgnored(ProviderType provider, String notificationId, String reason) {
        find(provider, notificationId).ifPresent(event -> event.markIgnored(reason));
    }

    @Transactional
    public void markFailed(ProviderType provider, String notificationId, String error) {
        find(provider, notificationId).ifPresent(event -> event.markFailed(error));
    }

    private Optional<PaymentEventEntity> find(ProviderType provider, String notificationId) {
        return repository.findByProviderAndNotificationId(provider, notificationId);
    }
}
