package com.latiendita.productService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
class ProductServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
