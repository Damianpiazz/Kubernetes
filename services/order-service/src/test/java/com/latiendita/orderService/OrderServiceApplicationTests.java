package com.latiendita.orderService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
class OrderServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
