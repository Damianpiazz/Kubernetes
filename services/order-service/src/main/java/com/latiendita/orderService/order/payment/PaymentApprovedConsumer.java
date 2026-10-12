package com.latiendita.orderService.order.payment;

import com.latiendita.orderService.order.OrderEntity;
import com.latiendita.orderService.order.OrderRepository;
import com.latiendita.orderService.order.OrderStatus;
import com.latiendita.orderService.order.dto.event.PaymentApprovedEvent;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentApprovedConsumer {

    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    public static final String PAYMENT_APPROVED = "PAYMENT_APPROVED";

    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = PAYMENT_EVENTS_TOPIC)
    @Transactional
    public void onPaymentEvent(String payload) {
        String type = readEventType(payload);
        if (!PAYMENT_APPROVED.equals(type)) {
            return;
        }

        PaymentApprovedEvent event;
        try {
            event = objectMapper.readValue(payload, PaymentApprovedEvent.class);
        } catch (Exception ex) {
            log.warn("Evento PAYMENT_APPROVED malformado descartado: {}", payload);
            return;
        }

        approve(event);
    }

    private String readEventType(String payload) {
        try {
            Map<?, ?> node = objectMapper.readValue(payload, Map.class);
            Object type = node.get("type");
            return type == null ? null : type.toString();
        } catch (Exception ex) {
            log.warn("Mensaje malformado en payment-events descartado: {}", payload);
            return null;
        }
    }

    private void approve(PaymentApprovedEvent event) {
        OrderEntity order = orderRepository.findById(event.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Se recibió PAYMENT_APPROVED para el pedido inexistente "
                                + event.orderId()));

        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("Pago aprobado para el pedido {} ya en estado {}; se ignora (idempotente)",
                    order.getId(), order.getStatus());
            return;
        }

        log.info("Pedido {} pasa de PENDING a PAID por el pago {}",
                order.getId(), event.paymentId());

        order.setStatus(OrderStatus.PAID);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
    }
}
