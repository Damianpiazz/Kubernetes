package com.latiendita.paymentService.payment;

import com.latiendita.paymentService.provider.ProviderType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByOrderId(Long orderId);

    Optional<PaymentEntity> findByProviderAndProviderPaymentId(
            ProviderType provider, String providerPaymentId);

    Optional<PaymentEntity> findByExternalReference(String externalReference);

    Optional<PaymentEntity> findFirstByOrderIdAndStatusIn(
            Long orderId, Collection<PaymentStatus> statuses);

    Page<PaymentEntity> findByCustomerId(Long customerId, Pageable pageable);

    boolean existsByOrderIdAndStatus(Long orderId, PaymentStatus status);
}
