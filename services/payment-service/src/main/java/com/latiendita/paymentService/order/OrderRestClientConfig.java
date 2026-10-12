package com.latiendita.paymentService.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OrderRestClientConfig {

    @Bean
    RestClient restClient(@Value("${order-service.base-url}") String baseUrl) {
        return RestClient.create(baseUrl);
    }
}
