package com.latiendita.paymentService.webhook;

import com.latiendita.paymentService.provider.ProviderType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEventEntity, Long> {

    Optional<PaymentEventEntity> findByProviderAndNotificationId(
            ProviderType provider, String notificationId);
}
