package com.latiendita.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "eureka.client.enabled=false",
        "jwt.secret=" + TestJwtFactory.TEST_SECRET,
        "jwt.issuer=" + TestJwtFactory.TEST_ISSUER,

        "gateway.ratelimit.default-capacity=1000",
        "gateway.ratelimit.default-refill-tokens=1000",
        "gateway.ratelimit.routes.auth.capacity=1000",
        "gateway.ratelimit.routes.auth.refill-tokens=1000"
})
@AutoConfigureTestRestTemplate
class GatewayRoutingIntegrationTest {

    private static HttpServer stub;
    private static int stubPort;

    private static volatile String lastUserHeader;
    private static volatile String lastCorrelationHeader;

    @BeforeAll
    static void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stubPort = stub.getAddress().getPort();
        stub.createContext("/", exchange -> {
            lastUserHeader = exchange.getRequestHeaders().getFirst("X-User-Id");
            lastCorrelationHeader = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
            byte[] body = "{\"stub\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        stub.start();
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + stubPort;
        registry.add("AUTH_SERVICE_BASE_URL", () -> base);
        registry.add("USER_SERVICE_BASE_URL", () -> base);
        registry.add("PRODUCT_SERVICE_BASE_URL", () -> base);
        registry.add("ORDER_SERVICE_BASE_URL", () -> base);
        registry.add("PAYMENT_SERVICE_BASE_URL", () -> base);
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void rechazaSinTokenEnRutaProtegida() {
        ResponseEntity<String> response = rest.getForEntity("/api/orders", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void enrutaConTokenEInyectaXUserId() {
        String token = TestJwtFactory.token(42);
        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"stub\":true");

        assertThat(lastUserHeader).isEqualTo("42");
    }

    @Test
    void sanitizaElXUserIdSpoofeado() {
        String token = TestJwtFactory.token(42);
        HttpHeaders headers = authHeaders(token);
        headers.set("X-User-Id", "999");

        rest.exchange("/api/orders", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(lastUserHeader).isEqualTo("42");
    }

    @Test
    void permiteSinTokenEnRutaPublicaAuth() {
        ResponseEntity<String> response = rest.postForEntity("/api/auth/login",
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void propagaElCorrelationId() {
        String token = TestJwtFactory.token(7);
        HttpHeaders headers = authHeaders(token);
        headers.set("X-Correlation-Id", "corr-int-test");

        ResponseEntity<String> response = rest.exchange("/api/orders", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("corr-int-test");
        assertThat(lastCorrelationHeader).isEqualTo("corr-int-test");
    }

    @Test
    void respondeElPreflightCorsSinToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "http://localhost:5173");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");
        headers.setBearerAuth(TestJwtFactory.token(1));

        ResponseEntity<Void> response = rest.exchange("/api/orders", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:5173");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
    }

    @Test
    void rechazaElPreflightDesdeOrigenNoPermitido() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ORIGIN, "http://evil.example");
        headers.set(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET");

        ResponseEntity<Void> response = rest.exchange("/api/orders", HttpMethod.OPTIONS,
                new HttpEntity<>(headers), Void.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    private static HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
