package com.latiendita.authService;

import static org.assertj.core.api.Assertions.assertThat;

import com.latiendita.authService.account.AccountRepository;
import com.latiendita.authService.account.AccountService;
import com.latiendita.authService.account.dto.RegisterRequest;
import com.latiendita.authService.outbox.OutboxEvent;
import com.latiendita.authService.outbox.OutboxEventRepository;
import com.latiendita.authService.outbox.OutboxRelay;
import com.latiendita.authService.outbox.OutboxStatus;
import com.latiendita.authService.outbox.OutboxWriter;
import java.time.Duration;
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
import org.springframework.context.annotation.Import;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@EmbeddedKafka(partitions = 1, topics = {AccountService.ACCOUNT_EVENTS_TOPIC})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "outbox.relay.poll-interval-ms=600000",
        "outbox.relay.max-attempts=3",
        "jwt.secret=test-secret-test-secret-test-secret-1234"
})
class AuthMessagingIntegrationTest {

    private static final String ACCOUNT_EVENTS = AccountService.ACCOUNT_EVENTS_TOPIC;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldPublishAccountCreatedToKafkaAndMarkOutboxEventAsSent() {
        accountService.register(new RegisterRequest("ana@mail.com", "ana", "secreta123"));

        Long accountId = accountRepository.findByEmail("ana@mail.com").orElseThrow().getId();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(ACCOUNT_EVENTS));

            outboxRelay.relay();

            ConsumerRecord<String, String> record =
                    awaitRecordForKey(consumer, String.valueOf(accountId));

            assertThat(record.value())
                    .contains("\"email\":\"ana@mail.com\"", "\"username\":\"ana\"")
                    .doesNotContain("secreta123");

            assertThat(outboxEventRepository.findAll()).singleElement().satisfies(event -> {
                assertThat(event.getEventType()).isEqualTo("ACCOUNT_CREATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getSentAt()).isNotNull();
                assertThat(event.getAttempts()).isZero();
            });
        }
    }

    @Test
    void shouldRetryFailedEventsAndStopAfterMaxAttempts() {
        Long retriable = register("retry@mail.com", "retry");
        failEvent("broker caído");

        Long exhausted = register("agotado@mail.com", "agotado");
        exhaustEvent();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(ACCOUNT_EVENTS));

            outboxRelay.relay();

            ConsumerRecord<String, String> record =
                    awaitRecordForKey(consumer, String.valueOf(retriable));
            assertThat(record.value()).contains("retry@mail.com");

            outboxRelay.relay();
            assertNoRecordForKey(consumer, String.valueOf(exhausted));

            assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("ACCOUNT_CREATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getAttempts()).isEqualTo(1);
            });
            assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("ACCOUNT_CREATED");
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

    private Long register(String email, String username) {
        accountService.register(new RegisterRequest(email, username, "secreta123"));
        return accountRepository.findByEmail(email).orElseThrow().getId();
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
}
