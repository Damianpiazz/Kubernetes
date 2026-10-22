package com.latiendita.userService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.userService.dto.UserRequest;
import com.latiendita.userService.outbox.OutboxEvent;
import com.latiendita.userService.outbox.OutboxEventRepository;
import com.latiendita.userService.outbox.OutboxStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@TestPropertySource(properties = "outbox.relay.poll-interval-ms=600000")
class UserApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void shouldCreateUserReturning201() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new UserRequest("ana@mail.com", "ana"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/users/")))
                .andExpect(jsonPath("$.email").value("ana@mail.com"))
                .andExpect(jsonPath("$.username").value("ana"));
    }

    @Test
    void shouldRejectDuplicateEmailWith409() throws Exception {
        userRepository.save(UserEntity.builder()
                .email("ana@mail.com")
                .username("otra")
                .build());

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new UserRequest("ana@mail.com", "pedro"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void shouldRejectInvalidRequestWith400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new UserRequest("no-es-un-email", "a"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldReturn404WhenUserDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/users/{id}", 999999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Usuario no encontrado"));
    }

    @Test
    void shouldPaginateUsers() throws Exception {
        userRepository.saveAll(List.of(
                user("ana1@mail.com", "ana1"),
                user("ana2@mail.com", "ana2"),
                user("ana3@mail.com", "ana3")));

        mockMvc.perform(get("/api/users").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));

        mockMvc.perform(get("/api/users").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void shouldDeleteUserThenReturn404() throws Exception {
        UserEntity created = userRepository.save(user("ana@mail.com", "ana"));

        mockMvc.perform(delete("/api/users/{id}", created.getId()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/users/{id}", created.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldPersistOutboxEventInSameTransactionOnCreate() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new UserRequest("ana@mail.com", "ana"))))
                .andExpect(status().isCreated());

        UserEntity persisted = userRepository.findByEmail("ana@mail.com").orElseThrow();
        List<OutboxEvent> events = outboxEventRepository.findAll();

        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getTopic()).isEqualTo("user-events");
        assertThat(event.getEventType()).isEqualTo("USER_CREATED");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(persisted.getId()));
        assertThat(event.getPayload()).contains("ana@mail.com");
    }

    @Test
    void shouldReturnMeUsingGatewayEmailHeader() throws Exception {
        userRepository.save(user("ana@mail.com", "ana"));

        mockMvc.perform(get("/api/users/me").header("X-User-Email", "ana@mail.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ana@mail.com"))
                .andExpect(jsonPath("$.username").value("ana"));
    }

    @Test
    void shouldReturn401WhenGatewayIdentityHeaderIsMissing() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autorizado"));
    }

    @Test
    void shouldReturn404WhenProfileIsNotCreatedYet() throws Exception {
        // El perfil se crea por el evento account-events: puede no existir
        // inmediatamente después del registro.
        mockMvc.perform(get("/api/users/me").header("X-User-Email", "nadie@mail.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Usuario no encontrado"));
    }

    private String body(UserRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    private UserEntity user(String email, String username) {
        return UserEntity.builder()
                .email(email)
                .username(username)
                .build();
    }
}