package com.latiendita.userService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.userService.dto.event.AccountCreatedEvent;
import com.latiendita.userService.dto.event.UserCreatedEvent;
import com.latiendita.userService.dto.event.UserUpdatedEvent;
import com.latiendita.userService.outbox.OutboxEvent;
import com.latiendita.userService.outbox.OutboxEventRepository;
import com.latiendita.userService.outbox.OutboxRelay;
import com.latiendita.userService.outbox.OutboxStatus;
import com.latiendita.userService.outbox.OutboxWriter;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = {UserService.USER_EVENTS_TOPIC, "account-events"})
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "outbox.relay.poll-interval-ms=600000",
        "outbox.relay.max-attempts=3"
})
class UserMessagingIntegrationTest {

    private static final String USER_EVENTS = UserService.USER_EVENTS_TOPIC;
    private static final String ACCOUNT_EVENTS = "account-events";
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldPublishUserCreatedToKafkaAndMarkOutboxEventAsSent() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana@mail.com\",\"username\":\"ana\"}"))
                .andExpect(status().isCreated());

        Long userId = userRepository.findByEmail("ana@mail.com").orElseThrow().getId();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(USER_EVENTS));

            outboxRelay.relay();

            ConsumerRecord<String, String> record =
                    awaitRecordForKey(consumer, String.valueOf(userId));

            assertThat(record.value()).contains("\"email\":\"ana@mail.com\"", "\"username\":\"ana\"");

            assertThat(outboxEventRepository.findAll()).singleElement().satisfies(event -> {
                assertThat(event.getEventType()).isEqualTo("USER_CREATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getSentAt()).isNotNull();
                assertThat(event.getAttempts()).isZero();
            });
        }
    }

    @Test
    void shouldRetryFailedEventsAndStopAfterMaxAttempts() {
        UserEntity retriable = userRepository.save(user("retry@mail.com", "retry"));
        outboxWriter.append(USER_EVENTS, retriable.getId(), "USER_UPDATED",
                new UserUpdatedEvent(retriable.getId(), retriable.getEmail(),
                        retriable.getUsername(), Instant.now()));
        failEvent("broker caído");

        UserEntity exhausted = userRepository.save(user("agotado@mail.com", "agotado"));
        outboxWriter.append(USER_EVENTS, exhausted.getId(), "USER_CREATED",
                new UserCreatedEvent(exhausted.getId(), exhausted.getEmail(),
                        exhausted.getUsername(), Instant.now()));
        exhaustEvent();

        try (KafkaConsumer<String, String> consumer = consumer()) {
            consumer.subscribe(List.of(USER_EVENTS));

            outboxRelay.relay();

            ConsumerRecord<String, String> record =
                    awaitRecordForKey(consumer, String.valueOf(retriable.getId()));
            assertThat(record.value()).contains("retry@mail.com");

            outboxRelay.relay();
            assertNoRecordForKey(consumer, String.valueOf(exhausted.getId()));

            assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("USER_UPDATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
                assertThat(event.getAttempts()).isEqualTo(1);
            });
            assertThat(outboxEventRepository.findAll()).anySatisfy(event -> {
                assertThat(event.getEventType()).isEqualTo("USER_CREATED");
                assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
                assertThat(event.getAttempts()).isEqualTo(MAX_ATTEMPTS);
            });
        }
    }

    @Test
    void shouldCreateProfileFromAccountCreatedEventIdempotently() throws Exception {
        String payload = objectMapper.writeValueAsString(
                new AccountCreatedEvent(7L, "nueva@mail.com", "nueva", Instant.now()));

        kafkaTemplate.send(ACCOUNT_EVENTS, "7", payload).get();
        await().atMost(POLL_TIMEOUT)
                .until(() -> userRepository.existsByEmail("nueva@mail.com"));

        kafkaTemplate.send(ACCOUNT_EVENTS, "7", payload).get();

        kafkaTemplate.send(ACCOUNT_EVENTS, "8", objectMapper.writeValueAsString(
                new AccountCreatedEvent(8L, "otra@mail.com", "otra", Instant.now()))).get();
        await().atMost(POLL_TIMEOUT)
                .until(() -> userRepository.existsByEmail("otra@mail.com"));

        assertThat(userRepository.findAll())
                .extracting(UserEntity::getEmail)
                .containsExactlyInAnyOrder("nueva@mail.com", "otra@mail.com");
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

    private UserEntity user(String email, String username) {
        return UserEntity.builder()
                .email(email)
                .username(username)
                .build();
    }
}
