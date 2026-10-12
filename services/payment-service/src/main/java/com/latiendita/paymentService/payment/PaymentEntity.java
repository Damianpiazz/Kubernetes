package com.latiendita.paymentService.payment;

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
import jakarta.persistence.Version;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "payments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payments_provider_payment",
                columnNames = {"provider", "provider_payment_id"}),
        @UniqueConstraint(name = "uk_payments_idempotency_key",
                columnNames = "idempotency_key")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProviderType provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @PositiveOrZero
    @Digits(integer = 10, fraction = 2)
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @NotNull
    @Size(max = 3)
    @Column(nullable = false, length = 3)
    private String currency;

    @Size(max = 64)
    @Column(name = "preference_id", length = 64)
    private String preferenceId;

    @Size(max = 500)
    @Column(name = "checkout_url", length = 500)
    private String checkoutUrl;

    @Size(max = 36)
    @Column(name = "external_reference", length = 36)
    private String externalReference;

    @Size(max = 64)
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Size(max = 64)
    @Column(name = "provider_payment_id", length = 64)
    private String providerPaymentId;

    @Size(max = 40)
    @Column(name = "payment_method_id", length = 40)
    private String paymentMethodId;

    @Size(max = 30)
    @Column(name = "payment_type", length = 30)
    private String paymentType;

    @Size(max = 100)
    @Column(name = "status_detail", length = 100)
    private String statusDetail;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
