package com.latiendita.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "jwt.secret=" + TestJwtFactory.TEST_SECRET,
        "jwt.issuer=" + TestJwtFactory.TEST_ISSUER
})
class GatewayApplicationTests {

    @Test
    void contextLoads() {
    }
}
