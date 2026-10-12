package com.latiendita.paymentService.webhook;

import com.latiendita.paymentService.provider.ProviderType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payment_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_events_notification",
                columnNames = {"provider", "notification_id"})
})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderType provider;

    @Column(name = "notification_id", nullable = false, length = 128)
    private String notificationId;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    @Column(name = "resource_id", nullable = false, length = 64)
    private String resourceId;

    @Column(length = 50)
    private String action;

    @Column(name = "signature_valid", nullable = false)
    private boolean signatureValid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentEventStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 500)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    public static PaymentEventEntity received(ProviderType provider, String notificationId,
                                              String resourceType, String resourceId,
                                              String action, boolean signatureValid) {
        PaymentEventEntity event = new PaymentEventEntity();
        event.provider = provider;
        event.notificationId = notificationId;
        event.resourceType = resourceType;
        event.resourceId = resourceId;
        event.action = action;
        event.signatureValid = signatureValid;
        event.status = PaymentEventStatus.RECEIVED;
        event.attempts = 0;
        event.createdAt = Instant.now();
        return event;
    }

    public void markProcessed() {
        this.status = PaymentEventStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.lastError = null;
    }

    public void markIgnored(String reason) {
        this.status = PaymentEventStatus.IGNORED;
        this.processedAt = Instant.now();
        this.lastError = truncate(reason);
    }

    public void markFailed(String error) {
        this.attempts++;
        this.status = PaymentEventStatus.FAILED;
        this.lastError = truncate(error);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
