package com.latiendita.authService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@TestPropertySource(properties = {
        "outbox.relay.poll-interval-ms=600000",
        "jwt.secret=test-secret-test-secret-test-secret-1234"
})
class AuthServiceApplicationTests {

    @Test
    void contextLoads() {
    }
}