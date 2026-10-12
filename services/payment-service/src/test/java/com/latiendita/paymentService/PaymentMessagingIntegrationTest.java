package com.latiendita.paymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.paymentService.order.OrderClient;
import com.latiendita.paymentService.order.OrderDto;
import com.latiendita.paymentService.outbox.OutboxEvent;
import com.latiendita.paymentService.outbox.OutboxEventRepository;
import com.latiendita.paymentService.outbox.OutboxRelay;
import com.latiendita.paymentService.outbox.OutboxStatus;
import com.latiendita.paymentService.payment.PaymentRepository;
import com.latiendita.paymentService.payment.PaymentService;
import com.latiendita.paymentService.provider.PaymentGateway;
import com.latiendita.paymentService.provider.ProviderType;
import com.latiendita.paymentService.webhook.PaymentEventRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
@EmbeddedKafka(partitions = 1, topics = PaymentService.PAYMENT_EVENTS_TOPIC)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "outbox.relay.poll-interval-ms=600000",
        "outbox.relay.max-attempts=3",
        "mercado-pago.access-token=TEST-access-token",
        "mercado-pago.webhook-secret=test-webhook-secret"
})
class PaymentMessagingIntegrationTest {

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @MockitoBean
    private OrderClient orderClient;

    @MockitoBean
    private PaymentGateway mercadopagoAdapter;

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAll();
        paymentEventRepository.deleteAll();
        paymentRepository.deleteAll();
        lenient().when(mercadopagoAdapter.type()).thenReturn(ProviderType.MERCADO_PAGO);
        lenient().when(mercadopagoAdapter.createPreference(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentGateway.GatewayPreference(
                        "pref-1", "https://mp/checkout?pref_id=pref-1"));
        lenient().when(orderClient.findById(7L)).thenReturn(pendingOrder());
    }

    @Test
    void shouldPublishPaymentCreatedToKafkaKeyedByOrderId() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":7,\"payerEmail\":\"ana@mail.com\"}"))
                .andExpect(status().isCreated());

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentService.PAYMENT_EVENTS_TOPIC));

            outboxRelay.relay();

            ConsumerRecord<String, String> record = awaitRecordForKey(consumer, "7");

            assertThat(record.value()).contains(
                    "\"type\":\"PAYMENT_CREATED\"", "\"orderId\":7",
                    "\"orderNumber\":\"ORD-7\"", "\"amount\":2049.48");

            assertThat(outboxEventRepository.findAll()).singleElement().satisfies(event -> {
                assertThat(event.getEventType()).isEqualTo("PAYMENT_CREATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getSentAt()).isNotNull();
                assertThat(event.getAttempts()).isZero();
            });
        }
    }

    @Test
    void shouldPublishPaymentApprovedKeyedByOrderIdAfterWebhook() throws Exception {
        createPayment();

        lenient().when(mercadopagoAdapter.getPayment("12345"))
                .thenReturn(new PaymentGateway.GatewayPayment("12345",
                        com.latiendita.paymentService.payment.PaymentStatus.APPROVED,
                        "accredited", "visa", "credit_card", new BigDecimal("2049.48"),
                        "ARS", "ORD-7"));

        mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", signature("12345"))
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"payment.updated\",\"type\":\"payment\","
                                + "\"id\":99,\"data\":{\"id\":\"12345\"}}"))
                .andExpect(status().isOk());

        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getEventType)
                .contains("PAYMENT_APPROVED");

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(PaymentService.PAYMENT_EVENTS_TOPIC));

            outboxRelay.relay();

            ConsumerRecord<String, String> record = awaitRecord(consumer, "7",
                    value -> value.contains("\"type\":\"PAYMENT_APPROVED\""));
            assertThat(record.value()).contains(
                    "\"providerPaymentId\":\"12345\"", "\"provider\":\"MERCADO_PAGO\"");
        }
    }

    private void createPayment() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":7,\"payerEmail\":\"ana@mail.com\"}"))
                .andExpect(status().isCreated());
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
        return awaitRecord(consumer, key, value -> true);
    }

    private ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer,
                                                       String key,
                                                       Predicate<String> valueMatcher) {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                if (key.equals(record.key()) && valueMatcher.test(record.value())) {
                    return record;
                }
            }
        }
        throw new AssertionError(
                "No se publicó ningún evento con key=" + key + " en " + POLL_TIMEOUT);
    }

    private String signature(String resourceId) throws Exception {
        String ts = "1704908010";
        String template = "id:" + resourceId + ";request-id:req-1;ts:" + ts + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        byte[] hash = mac.doFinal(template.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return "ts=" + ts + ",v1=" + hex;
    }

    private OrderDto pendingOrder() {
        return new OrderDto(7L, "ORD-7", 1L, "PENDING", new BigDecimal("2049.48"),
                List.of(new OrderDto.Item(42L, "Notebook Lenovo", "NB-LEN-001", 2,
                        new BigDecimal("999.99"), new BigDecimal("1999.98"))));
    }
}
