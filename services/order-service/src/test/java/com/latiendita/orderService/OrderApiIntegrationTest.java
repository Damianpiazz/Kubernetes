package com.latiendita.orderService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.orderService.order.OrderEntity;
import com.latiendita.orderService.order.OrderItemEntity;
import com.latiendita.orderService.order.OrderRepository;
import com.latiendita.orderService.order.OrderStatus;
import com.latiendita.orderService.outbox.OutboxEvent;
import com.latiendita.orderService.outbox.OutboxEventRepository;
import com.latiendita.orderService.outbox.OutboxStatus;
import com.latiendita.orderService.product.ProductClient;
import com.latiendita.orderService.product.ProductDto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@TestPropertySource(properties = "outbox.relay.poll-interval-ms=600000")
class OrderApiIntegrationTest {

    private static final String CREATE_BODY =
            """
            {"items":[{"productId":42,"quantity":2}],"total":1999.98}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @MockitoBean
    private ProductClient productClient;

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAll();
        orderRepository.deleteAll();
        lenient().when(productClient.findById(42L)).thenReturn(
                new ProductDto(42L, "Notebook Lenovo", "https://cdn/notebook.png",
                        "NB-LEN-001", new BigDecimal("999.99"), 5, 7L));
    }

    @Test
    void shouldCreateOrderReturning201WithCalculatedTotal() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/orders/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.total").value(1999.98))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].unitPrice").value(999.99))
                .andExpect(jsonPath("$.items[0].subtotal").value(1999.98))
                .andExpect(jsonPath("$.items[0].sku").value("NB-LEN-001"));
    }

    @Test
    void shouldRejectClientTotalMismatchWith400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"productId\":42,\"quantity\":2}],\"total\":1.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Pedido inválido"));
    }

    @Test
    void shouldRejectMissingUserIdHeaderWith401() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autorizado"));
    }

    @Test
    void shouldReturn404WhenOrderDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999999L).header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Pedido no encontrado"));
    }

    @Test
    void shouldReturn403WhenOrderBelongsToAnotherCustomer() throws Exception {
        OrderEntity persisted = orderRepository.save(order(2L, OrderStatus.PENDING, "50.00"));

        mockMvc.perform(get("/api/orders/{id}", persisted.getId()).header("X-User-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
    }

    @Test
    void shouldListOnlyOrdersOfCurrentUserPaginated() throws Exception {
        orderRepository.saveAll(List.of(
                order(1L, OrderStatus.PENDING, "10.00"),
                order(1L, OrderStatus.PAID, "20.00"),
                order(2L, OrderStatus.PENDING, "999.00")));

        mockMvc.perform(get("/api/orders").header("X-User-Id", "1")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldCancelPendingOrder() throws Exception {
        OrderEntity persisted = orderRepository.save(order(1L, OrderStatus.PENDING, "10.00"));

        mockMvc.perform(post("/api/orders/{id}/cancel", persisted.getId())
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Me arrepentí\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Me arrepentí"))
                .andExpect(jsonPath("$.refundRequired").value(false));
    }

    @Test
    void shouldRejectInvalidTransitionWith409() throws Exception {
        OrderEntity persisted = orderRepository.save(order(1L, OrderStatus.PENDING, "10.00"));

        mockMvc.perform(post("/api/orders/{id}/status", persisted.getId())
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DELIVERED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Transición de estado inválida"));
    }

    @Test
    void shouldPersistOutboxEventInSameTransactionOnCreate() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        OrderEntity persisted = orderRepository.findAll().get(0);
        List<OutboxEvent> events = outboxEventRepository.findAll();

        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getTopic()).isEqualTo("order-events");
        assertThat(event.getEventType()).isEqualTo("ORDER_CREATED");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(persisted.getId()));
        assertThat(event.getPayload()).contains("\"productId\":42", "\"unitPrice\":999.99");
    }

    private OrderEntity order(Long customerId, OrderStatus status, String total) {
        OrderEntity order = OrderEntity.builder()
                .orderNumber(UUID.randomUUID().toString())
                .customerId(customerId)
                .status(status)
                .total(new BigDecimal(total))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        order.addItem(OrderItemEntity.builder()
                .order(order)
                .productId(42L)
                .productTitle("Notebook Lenovo")
                .sku("NB-LEN-001")
                .quantity(1)
                .unitPrice(new BigDecimal(total))
                .subtotal(new BigDecimal(total))
                .build());
        return order;
    }
}
