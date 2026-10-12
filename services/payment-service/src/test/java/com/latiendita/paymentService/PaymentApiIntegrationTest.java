package com.latiendita.paymentService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.latiendita.paymentService.order.OrderClient;
import com.latiendita.paymentService.order.OrderDto;
import com.latiendita.paymentService.outbox.OutboxEvent;
import com.latiendita.paymentService.outbox.OutboxEventRepository;
import com.latiendita.paymentService.outbox.OutboxStatus;
import com.latiendita.paymentService.payment.PaymentEntity;
import com.latiendita.paymentService.payment.PaymentRepository;
import com.latiendita.paymentService.payment.PaymentStatus;
import com.latiendita.paymentService.provider.PaymentGateway;
import com.latiendita.paymentService.provider.ProviderType;
import com.latiendita.paymentService.webhook.PaymentEventRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "outbox.relay.poll-interval-ms=600000",
        "mercado-pago.access-token=TEST-access-token",
        "mercado-pago.webhook-secret=test-webhook-secret"
})
class PaymentApiIntegrationTest {

    private static final String CREATE_BODY = """
            {"orderId":7,"payerEmail":"ana@mail.com"}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @MockitoBean
    private OrderClient orderClient;

    @MockitoBean
    private PaymentGateway mercadopagoAdapter;

    @BeforeEach
    void setup() {
        outboxEventRepository.deleteAll();
        paymentEventRepository.deleteAll();
        paymentRepository.deleteAll();
        lenient().when(mercadopagoAdapter.type()).thenReturn(ProviderType.MERCADO_PAGO);
        lenient().when(mercadopagoAdapter.createPreference(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PaymentGateway.GatewayPreference(
                        "pref-1", "https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=pref-1"));
        lenient().when(orderClient.findById(7L)).thenReturn(pendingOrder());
    }

    @Test
    void shouldCreatePaymentReturning201WithCheckoutUrl() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/payments/")))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.provider").value("MERCADO_PAGO"))
                .andExpect(jsonPath("$.amount").value(2049.48))
                .andExpect(jsonPath("$.currency").value("ARS"))
                .andExpect(jsonPath("$.preferenceId").value("pref-1"))
                .andExpect(jsonPath("$.checkoutUrl")
                        .value("https://www.mercadopago.com.ar/checkout/v1/redirect?pref_id=pref-1"));
    }

    @Test
    void shouldRejectInvalidEmailWith400() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":7,\"payerEmail\":\"no-es-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos inválidos"))
                .andExpect(jsonPath("$.errors[0]").value(
                        "payerEmail: debe ser un correo válido"));
    }

    @Test
    void shouldRejectMissingOrderIdWith400() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payerEmail\":\"ana@mail.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]")
                        .value("orderId: no debe estar en blanco"));
    }

    @Test
    void shouldRejectMissingUserIdHeaderWith401() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("No autorizado"));
    }

    @Test
    void shouldReturn404WhenPaymentDoesNotExist() throws Exception {
        mockMvc.perform(get("/api/payments/{id}", 999999L).header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Pago no encontrado"));
    }

    @Test
    void shouldReturn403WhenPaymentBelongsToAnotherCustomer() throws Exception {
        PaymentEntity persisted = paymentRepository.save(payment(2L, PaymentStatus.PENDING));

        mockMvc.perform(get("/api/payments/{id}", persisted.getId()).header("X-User-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Acceso denegado"));
    }

    @Test
    void shouldListOnlyPaymentsOfCurrentUserPaginated() throws Exception {
        paymentRepository.saveAll(List.of(
                payment(1L, PaymentStatus.PENDING),
                payment(1L, PaymentStatus.APPROVED),
                payment(2L, PaymentStatus.PENDING)));

        mockMvc.perform(get("/api/payments").header("X-User-Id", "1")
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void shouldReturnPaymentByOrderIdForItsOwner() throws Exception {
        PaymentEntity persisted = paymentRepository.save(payment(1L, PaymentStatus.PENDING));

        mockMvc.perform(get("/api/payments/order/{orderId}", 7L).header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(persisted.getId()))
                .andExpect(jsonPath("$.orderId").value(7));
    }

    @Test
    void shouldReturn404WhenNoPaymentExistsForTheOrder() throws Exception {
        mockMvc.perform(get("/api/payments/order/{orderId}", 4242L).header("X-User-Id", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Pago no encontrado"));
    }

    @Test
    void shouldRejectPaymentForOrderThatIsAlreadyPaidWith409() throws Exception {
        lenient().when(orderClient.findById(7L)).thenReturn(orderWithStatus("PAID"));

        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Pago duplicado"));
    }

    @Test
    void shouldReturn503WhenPaymentGatewayHasNoAccessToken() throws Exception {
        lenient().when(mercadopagoAdapter.createPreference(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new com.latiendita.paymentService.exception
                        .PaymentGatewayUnavailableException(
                        "MERCADO_PAGO_ACCESS_TOKEN no está configurado"));

        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Servicio no disponible"));
    }

    @Test
    void shouldPersistOutboxEventInSameTransactionOnCreate() throws Exception {
        mockMvc.perform(post("/api/payments")
                        .header("X-User-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREATE_BODY))
                .andExpect(status().isCreated());

        PaymentEntity persisted = paymentRepository.findAll().get(0);
        List<OutboxEvent> events = outboxEventRepository.findAll();

        assertThat(events).hasSize(1);
        OutboxEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getTopic()).isEqualTo("payment-events");
        assertThat(event.getEventType()).isEqualTo("PAYMENT_CREATED");
        assertThat(event.getAggregateId()).isEqualTo(String.valueOf(persisted.getOrderId()));
        assertThat(event.getPayload()).contains("\"orderId\":7", "\"amount\":2049.48");
    }

    @Test
    void shouldAcceptWebhookWithValidSignatureAndApprovePayment() throws Exception {
        PaymentEntity payment = paymentRepository.save(payment(1L, PaymentStatus.PENDING));

        lenient().when(mercadopagoAdapter.getPayment("12345"))
                .thenReturn(new PaymentGateway.GatewayPayment("12345", PaymentStatus.APPROVED,
                        "accredited", "visa", "credit_card", new BigDecimal("2049.48"),
                        "ARS", "ORD-7"));

        String signature = signature("12345");

        MvcResult result = mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", signature)
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"payment.updated\",\"type\":\"payment\","
                                + "\"id\":99,\"data\":{\"id\":\"12345\"}}"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(reloaded.getProviderPaymentId()).isEqualTo("12345");
        assertThat(reloaded.getPaidAt()).isNotNull();
    }

    @Test
    void shouldIgnoreWebhookWithInvalidSignatureAndKeepPaymentPending() throws Exception {
        PaymentEntity payment = paymentRepository.save(payment(1L, PaymentStatus.PENDING));

        mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", "ts=1704908010,v1=deadbeef")
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"payment.updated\",\"type\":\"payment\","
                                + "\"id\":99,\"data\":{\"id\":\"12345\"}}"))
                .andExpect(status().isOk());

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(reloaded.getProviderPaymentId()).isNull();

        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void shouldAlwaysAcknowledgeWebhookEvenWhenGatewayFails() throws Exception {
        PaymentEntity payment = paymentRepository.save(payment(1L, PaymentStatus.PENDING));

        lenient().when(mercadopagoAdapter.getPayment("12345"))
                .thenThrow(new com.latiendita.paymentService.exception
                        .PaymentGatewayUnavailableException("MP caído"));

        mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", signature("12345"))
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"payment.updated\",\"type\":\"payment\","
                                + "\"id\":99,\"data\":{\"id\":\"12345\"}}"))
                .andExpect(status().isOk());

        PaymentEntity reloaded = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void shouldRecordEveryWebhookInTheInbox() throws Exception {
        paymentRepository.save(payment(1L, PaymentStatus.PENDING));

        mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", signature("12345"))
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"payment.updated\",\"type\":\"payment\","
                                + "\"id\":99,\"data\":{\"id\":\"12345\"}}"))
                .andExpect(status().isOk());

        assertThat(paymentEventRepository.count()).isEqualTo(1);
    }

    @Test
    void shouldAcknowledgeDuplicatedWebhookOnlyOnce() throws Exception {
        paymentRepository.save(payment(1L, PaymentStatus.PENDING));

        String body = "{\"action\":\"payment.updated\",\"type\":\"payment\","
                + "\"id\":99,\"data\":{\"id\":\"12345\"}}";

        mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", signature("12345"))
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/webhooks/mercado-pago")
                        .header("x-signature", signature("12345"))
                        .header("x-request-id", "req-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(paymentEventRepository.count()).isEqualTo(1);
    }

    private String signature(String resourceId) throws Exception {
        String ts = "1704908010";
        String template = "id:" + resourceId + ";request-id:req-1;ts:" + ts + ";";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("test-webhook-secret".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        byte[] hash = mac.doFinal(template.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return "ts=" + ts + ",v1=" + hex;
    }

    private OrderDto pendingOrder() {
        return orderWithStatus("PENDING");
    }

    private OrderDto orderWithStatus(String status) {
        return new OrderDto(7L, "ORD-7", 1L, status, new BigDecimal("2049.48"),
                List.of(new OrderDto.Item(42L, "Notebook Lenovo", "NB-LEN-001", 2,
                                new BigDecimal("999.99"), new BigDecimal("1999.98")),
                        new OrderDto.Item(7L, "Mouse", "MS-001", 1,
                                new BigDecimal("49.50"), new BigDecimal("49.50"))));
    }

    private PaymentEntity payment(Long customerId, PaymentStatus status) {
        return PaymentEntity.builder()
                .orderId(7L)
                .customerId(customerId)
                .provider(ProviderType.MERCADO_PAGO)
                .status(status)
                .amount(new BigDecimal("2049.48"))
                .currency("ARS")
                .externalReference("ORD-7")
                .idempotencyKey("ORD-7-" + customerId + "-" + System.nanoTime())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}
