package com.latiendita.orderService.product;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    RestClient restClient(@Value("${product-service.base-url}") String baseUrl) {
        return RestClient.create(baseUrl);
    }
}
