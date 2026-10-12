package com.latiendita.orderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.orderService.order.OrderEntity;
import com.latiendita.orderService.order.OrderItemEntity;
import com.latiendita.orderService.order.OrderRepository;
import com.latiendita.orderService.order.OrderService;
import com.latiendita.orderService.order.OrderStatus;
import com.latiendita.orderService.order.dto.event.OrderStatusChangedEvent;
import com.latiendita.orderService.outbox.OutboxEvent;
import com.latiendita.orderService.outbox.OutboxEventRepository;
import com.latiendita.orderService.outbox.OutboxRelay;
import com.latiendita.orderService.outbox.OutboxStatus;
import com.latiendita.orderService.outbox.OutboxWriter;
import com.latiendita.orderService.product.ProductClient;
import com.latiendita.orderService.product.ProductDto;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = OrderService.ORDER_EVENTS_TOPIC)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "outbox.relay.poll-interval-ms=600000",
        "outbox.relay.max-attempts=3"
})
class OrderMessagingIntegrationTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @MockitoBean
    private ProductClient productClient;

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        lenient().when(productClient.findById(42L)).thenReturn(
                new ProductDto(42L, "Notebook Lenovo", "https://cdn/notebook.png",
                        "NB-LEN-001", new BigDecimal("999.99"), 5, 7L));
    }

    @Test
    void shouldPublishOrderCreatedToKafkaAndMarkOutboxEventAsSent() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":42,\"quantity\":2}]}"))
                .andExpect(status().isCreated());

        Long orderId = orderRepository.findAll().get(0).getId();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(OrderService.ORDER_EVENTS_TOPIC));

            outboxRelay.relay();

            ConsumerRecord<String, String> record =
                    awaitRecordForKey(consumer, String.valueOf(orderId));

            assertThat(record.value()).contains(
                    "\"unitPrice\":999.99", "\"subtotal\":1999.98", "\"quantity\":2");

            assertThat(outboxEventRepository.findAll()).singleElement().satisfies(event -> {
                assertThat(event.getEventType()).isEqualTo("ORDER_CREATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getSentAt()).isNotNull();
                assertThat(event.getAttempts()).isZero();
            });
        }
    }

    @Test
    void shouldRetryFailedEventsAndStopAfterMaxAttempts() {
        OrderEntity retriable = orderRepository.save(order(1L));
        outboxWriter.append(OrderService.ORDER_EVENTS_TOPIC, retriable.getId(),
                "ORDER_STATUS_CHANGED",
                new OrderStatusChangedEvent(retriable.getId(), retriable.getOrderNumber(),
                        retriable.getCustomerId(), OrderStatus.PENDING, OrderStatus.PAID,
                        Instant.now()));
        failEvent("broker caído");

        OrderEntity exhausted = orderRepository.save(order(2L));
        outboxWriter.append(OrderService.ORDER_EVENTS_TOPIC, exhausted.getId(),
                "ORDER_STATUS_CHANGED",
                new OrderStatusChangedEvent(exhausted.getId(), exhausted.getOrderNumber(),
                        exhausted.getCustomerId(), OrderStatus.PENDING, OrderStatus.PAID,
                        Instant.now()));
        exhaustEvent();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(OrderService.ORDER_EVENTS_TOPIC));

            outboxRelay.relay();

            ConsumerRecord<String, String> record =
                    awaitRecordForKey(consumer, String.valueOf(retriable.getId()));
            assertThat(record.value()).contains("\"from\":\"PENDING\"", "\"to\":\"PAID\"");

            outboxRelay.relay();
            assertNoRecordForKey(consumer, String.valueOf(exhausted.getId()));

            assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("ORDER_STATUS_CHANGED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getAttempts()).isEqualTo(1);
            });
            assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("ORDER_STATUS_CHANGED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
                assertThat(event.getAttempts()).isEqualTo(MAX_ATTEMPTS);
            });
        }
    }

    private KafkaConsumer<String, String> consumer() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "assert-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class.getName());
        return new KafkaConsumer<>(properties);
    }

    private ConsumerRecord<String, String> awaitRecordForKey(
            KafkaConsumer<String, String> consumer, String key) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                if (key.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError(
                "No se publicó ningún evento con key=" + key + " en " + POLL_TIMEOUT);
    }

    private void assertNoRecordForKey(KafkaConsumer<String, String> consumer, String key) {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                assertThat(record.key())
                        .as("el evento agotado no debe publicarse")
                        .isNotEqualTo(key);
            }
        }
    }

    private void failEvent(String error) {
        OutboxEvent event = outboxEventRepository.findAll().get(0);
        event.markFailed(error);
        outboxEventRepository.save(event);
    }

    private void exhaustEvent() {
        OutboxEvent event = pendingEvent();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            event.markFailed("intent " + attempt);
        }
        outboxEventRepository.save(event);
    }

    private OutboxEvent pendingEvent() {
        return outboxEventRepository.findAll().stream()
                .filter(event -> event.getStatus() == OutboxStatus.PENDING)
                .findFirst()
                .orElseThrow();
    }

    private OrderEntity order(Long customerId) {
        OrderEntity order = OrderEntity.builder()
                .orderNumber(UUID.randomUUID().toString())
                .customerId(customerId)
                .status(OrderStatus.PENDING)
                .total(BigDecimal.TEN)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        order.addItem(OrderItemEntity.builder()
                .order(order)
                .productId(42L)
                .productTitle("Notebook Lenovo")
                .sku("NB-LEN-001")
                .quantity(1)
                .unitPrice(BigDecimal.TEN)
                .subtotal(BigDecimal.TEN)
                .build());
        return order;
    }
}
