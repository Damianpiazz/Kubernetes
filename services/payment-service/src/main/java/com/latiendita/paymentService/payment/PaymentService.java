package com.latiendita.paymentService.payment;

import com.latiendita.paymentService.CurrentUser;
import com.latiendita.paymentService.exception.DuplicatePaymentException;
import com.latiendita.paymentService.exception.InvalidPaymentException;
import com.latiendita.paymentService.exception.OrderNotFoundException;
import com.latiendita.paymentService.exception.PaymentAccessDeniedException;
import com.latiendita.paymentService.exception.PaymentNotFoundException;
import com.latiendita.paymentService.order.OrderClient;
import com.latiendita.paymentService.order.OrderDto;
import com.latiendita.paymentService.outbox.OutboxWriter;
import com.latiendita.paymentService.payment.dto.CreatePaymentRequest;
import com.latiendita.paymentService.payment.dto.PaymentMapper;
import com.latiendita.paymentService.payment.dto.PaymentResponse;
import com.latiendita.paymentService.payment.dto.event.PaymentApprovedEvent;
import com.latiendita.paymentService.payment.dto.event.PaymentCancelledEvent;
import com.latiendita.paymentService.payment.dto.event.PaymentCreatedEvent;
import com.latiendita.paymentService.payment.dto.event.PaymentRejectedEvent;
import com.latiendita.paymentService.provider.PaymentGateway;
import com.latiendita.paymentService.provider.PaymentGatewayFactory;
import com.latiendita.paymentService.provider.ProviderType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentService {

    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final OrderClient orderClient;
    private final PaymentGatewayFactory gatewayFactory;

    @Value("${payment.provider}")
    private ProviderType provider;

    @Value("${payment.default-currency}")
    private String defaultCurrency;

    @Value("${mercado-pago.notification-url}")
    private String notificationUrl;

    @Value("${mercado-pago.back-url-success}")
    private String backUrlSuccess;

    @Value("${mercado-pago.back-url-failure}")
    private String backUrlFailure;

    @Value("${mercado-pago.back-url-pending}")
    private String backUrlPending;

    public PaymentResponse create(CreatePaymentRequest request) {
        Long customerId = CurrentUser.id();
        OrderDto order = findOwnedOrder(request.orderId(), customerId);
        validateOrderIsPayable(order);

        PaymentGateway gateway = gatewayFactory.forProvider(provider);

        PaymentEntity payment = PaymentEntity.builder()
                .orderId(order.id())
                .customerId(customerId)
                .provider(provider)
                .status(PaymentStatus.PENDING)
                .amount(order.total())
                .currency(defaultCurrency)
                .externalReference(order.orderNumber())
                .idempotencyKey(order.orderNumber())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        PaymentGateway.GatewayPreference preference = gateway.createPreference(
                new PaymentGateway.GatewayPreferenceRequest(
                        order.orderNumber(),
                        order.total(),
                        defaultCurrency,
                        request.payerEmail(),
                        itemsFromOrder(order),
                        notificationUrl,
                        backUrlSuccess,
                        backUrlFailure,
                        backUrlPending));

        payment.setPreferenceId(preference.preferenceId());
        payment.setCheckoutUrl(preference.checkoutUrl());

        PaymentEntity saved = paymentRepository.save(payment);

        outboxWriter.append(PAYMENT_EVENTS_TOPIC, saved.getOrderId(), "PAYMENT_CREATED",
                new PaymentCreatedEvent("PAYMENT_CREATED", saved.getId(), saved.getOrderId(),
                        saved.getExternalReference(), saved.getCustomerId(),
                        saved.getAmount(), saved.getCurrency(), Instant.now()));

        return PaymentMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findById(Long paymentId) {
        return PaymentMapper.toResponse(findOwnedEntity(paymentId));
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> findByCustomer(Pageable pageable) {
        return paymentRepository.findByCustomerId(CurrentUser.id(), pageable)
                .map(PaymentMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByOrderId(Long orderId) {
        PaymentEntity payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(
                        "No existe un pago registrado para el pedido " + orderId));
        if (!payment.getCustomerId().equals(CurrentUser.id())) {
            throw new PaymentAccessDeniedException(payment.getId());
        }
        return PaymentMapper.toResponse(payment);
    }

    @Transactional
    public void applyFromProvider(ProviderType providerType, String providerPaymentId) {
        PaymentGateway gateway = gatewayFactory.forProvider(providerType);
        PaymentGateway.GatewayPayment gatewayPayment = gateway.getPayment(providerPaymentId);

        PaymentEntity payment = findPayment(providerType, providerPaymentId,
                gatewayPayment.externalReference());

        PaymentStatus target = gatewayPayment.status();

        if (!payment.getStatus().canTransitionTo(target)) {
            log.info("Notificación obsoleta ignorada: pago {} en {} y el proveedor informa {}",
                    payment.getId(), payment.getStatus(), target);
            return;
        }

        if (gatewayPayment.transactionAmount() != null
                && gatewayPayment.transactionAmount().compareTo(payment.getAmount()) != 0) {
            throw new InvalidPaymentException(
                    "El monto informado por el proveedor (" + gatewayPayment.transactionAmount()
                            + ") no coincide con el monto registrado (" + payment.getAmount() + ")");
        }

        PaymentStatus from = payment.getStatus();
        payment.setStatus(target);
        payment.setStatusDetail(gatewayPayment.statusDetail());
        payment.setPaymentMethodId(gatewayPayment.paymentMethodId());
        payment.setPaymentType(gatewayPayment.paymentType());
        payment.setProviderPaymentId(providerPaymentId);
        payment.setUpdatedAt(Instant.now());

        if (target == PaymentStatus.APPROVED && payment.getPaidAt() == null) {
            payment.setPaidAt(Instant.now());
        }

        PaymentEntity saved = paymentRepository.save(payment);
        publishStatusEvent(saved, from);
    }

    private PaymentEntity findPayment(ProviderType providerType, String providerPaymentId,
                                      String externalReference) {
        Optional<PaymentEntity> byProviderPaymentId = paymentRepository
                .findByProviderAndProviderPaymentId(providerType, providerPaymentId);
        if (byProviderPaymentId.isPresent()) {
            return byProviderPaymentId.get();
        }

        if (externalReference != null && !externalReference.isBlank()) {
            Optional<PaymentEntity> byExternalReference =
                    paymentRepository.findByExternalReference(externalReference);
            if (byExternalReference.isPresent()) {
                return byExternalReference.get();
            }
        }

        throw new PaymentNotFoundException(
                "No se encontró un pago registrado para el recurso " + providerPaymentId);
    }

    private void publishStatusEvent(PaymentEntity payment, PaymentStatus from) {
        Instant occurredAt = Instant.now();
        Long key = payment.getOrderId();
        switch (payment.getStatus()) {
            case APPROVED -> outboxWriter.append(PAYMENT_EVENTS_TOPIC, key,
                    "PAYMENT_APPROVED",
                    new PaymentApprovedEvent("PAYMENT_APPROVED", payment.getId(),
                            payment.getOrderId(), payment.getExternalReference(),
                            payment.getCustomerId(), payment.getAmount(), payment.getCurrency(),
                            payment.getProvider().name(), payment.getProviderPaymentId(),
                            occurredAt));
            case REJECTED -> outboxWriter.append(PAYMENT_EVENTS_TOPIC, key,
                    "PAYMENT_REJECTED",
                    new PaymentRejectedEvent("PAYMENT_REJECTED", payment.getId(),
                            payment.getOrderId(), payment.getCustomerId(),
                            payment.getStatusDetail(), occurredAt));
            case CANCELLED -> outboxWriter.append(PAYMENT_EVENTS_TOPIC, key,
                    "PAYMENT_CANCELLED",
                    new PaymentCancelledEvent("PAYMENT_CANCELLED", payment.getId(),
                            payment.getOrderId(), payment.getCustomerId(), occurredAt));
            default -> log.info("Pago {} pasó de {} a {} sin evento asociado",
                    payment.getId(), from, payment.getStatus());
        }
    }

    private void validateOrderIsPayable(OrderDto order) {
        if ("PAID".equals(order.status())) {
            throw new DuplicatePaymentException(order.id());
        }
        if (!"PENDING".equals(order.status())) {
            throw new InvalidPaymentException(
                    "El pedido " + order.id() + " no está pendiente de pago (estado actual: "
                            + order.status() + ")");
        }
    }

    private OrderDto findOwnedOrder(Long orderId, Long customerId) {
        OrderDto order = orderClient.findById(orderId);
        if (order.customerId() != null && !order.customerId().equals(customerId)) {
            throw new OrderNotFoundException(orderId);
        }
        return order;
    }

    private PaymentEntity findOwnedEntity(Long paymentId) {
        PaymentEntity payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        if (!payment.getCustomerId().equals(CurrentUser.id())) {
            throw new PaymentAccessDeniedException(paymentId);
        }
        return payment;
    }

    private List<PaymentGateway.GatewayPreferenceItem> itemsFromOrder(OrderDto order) {
        return order.items().stream()
                .map(item -> new PaymentGateway.GatewayPreferenceItem(
                        String.valueOf(item.productId()),
                        item.productTitle(),
                        item.quantity(),
                        item.unitPrice()))
                .toList();
    }
}
