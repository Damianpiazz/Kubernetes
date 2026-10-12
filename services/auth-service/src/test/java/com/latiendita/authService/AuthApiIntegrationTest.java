package com.latiendita.authService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.authService.account.AccountRepository;
import com.latiendita.authService.account.dto.LoginRequest;
import com.latiendita.authService.account.dto.RegisterRequest;
import com.latiendita.authService.outbox.OutboxEvent;
import com.latiendita.authService.outbox.OutboxEventRepository;
import com.latiendita.authService.outbox.OutboxStatus;
import com.latiendita.authService.token.JwtService;
import com.latiendita.authService.token.RefreshTokenEntity;
import com.latiendita.authService.token.RefreshTokenRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "outbox.relay.poll-interval-ms=600000",
        "jwt.secret=test-secret-test-secret-test-secret-1234"
})
class AuthApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void cleanDatabase() {
        outboxEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void shouldRegisterReturning201WithoutPasswordAndWritingOutbox() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new RegisterRequest("ana@mail.com", "ana", "secreta123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("ana@mail.com"))
                .andExpect(jsonPath("$.username").value("ana"))
                .andExpect(jsonPath("$.password").doesNotExist());

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(events.get(0).getEventType()).isEqualTo("ACCOUNT_CREATED");
        assertThat(events.get(0).getTopic()).isEqualTo("account-events");
    }

    @Test
    void shouldRejectDuplicateRegisterWith409() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new RegisterRequest("ana@mail.com", "ana", "secreta123"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new RegisterRequest("ana@mail.com", "otra", "secreta123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Recurso duplicado"));
    }

    @Test
    void shouldLoginReturningDecodableJwt() throws Exception {
        register("ana@mail.com", "ana", "secreta123");

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("ana@mail.com", "secreta123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        Map<?, ?> tokens = objectMapper.readValue(body, Map.class);
        String accessToken = (String) tokens.get("accessToken");

        var jwt = jwtService.decode(accessToken);
        assertThat(jwt.getSubject()).isNotBlank();
        assertThat(jwt.getClaimAsString("email")).isEqualTo("ana@mail.com");
    }

    @Test
    void shouldRejectLoginWithWrongPasswordUsing401() throws Exception {
        register("ana@mail.com", "ana", "secreta123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("ana@mail.com", "mala"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autorizado"));
    }

    @Test
    void shouldRefreshAndRotateTokens() throws Exception {
        register("ana@mail.com", "ana", "secreta123");
        String refresh = login("ana@mail.com", "secreta123");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
    }

    @Test
    void shouldRejectReusedRefreshTokenWith401() throws Exception {
        register("ana@mail.com", "ana", "secreta123");
        String refresh = login("ana@mail.com", "secreta123");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldLogoutReturning204() throws Exception {
        register("ana@mail.com", "ana", "secreta123");
        String refresh = login("ana@mail.com", "secreta123");

        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", refresh))))
                .andExpect(status().isNoContent());
    }

    @Test
    void shouldRejectInvalidRegisterWith400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new RegisterRequest("no-email", "a", "123"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void shouldLinkReplacedByWhenRefreshing() throws Exception {
        register("ana@mail.com", "ana", "secreta123");
        String oldRefresh = login("ana@mail.com", "secreta123");

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        List<RefreshTokenEntity> tokens = refreshTokenRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        assertThat(tokens).hasSize(2);

        RefreshTokenEntity old = tokens.get(0);
        RefreshTokenEntity replacement = tokens.get(1);
        assertThat(old.isRevoked()).isTrue();
        assertThat(old.getReplacedBy()).isEqualTo(replacement.getId());
        assertThat(replacement.isRevoked()).isFalse();
        assertThat(replacement.getReplacedBy()).isNull();
    }

    @Test
    void shouldRejectProtectedPathWithoutTokenWith401() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAcceptValidBearerTokenOnProtectedPath() throws Exception {
        register("ana@mail.com", "ana", "secreta123");
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest("ana@mail.com", "secreta123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> tokens = objectMapper.readValue(body, Map.class);
        String accessToken = (String) tokens.get("accessToken");

        mockMvc.perform(get("/api/admin/ping")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());
    }

    private void register(String email, String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new RegisterRequest(email, username, password))))
                .andExpect(status().isCreated());
    }

    private String login(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> tokens = objectMapper.readValue(body, Map.class);
        return (String) tokens.get("refreshToken");
    }

    private String body(Object request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }
}