package com.latiendita.paymentService.provider.mercadopago;

import static org.assertj.core.api.Assertions.assertThat;

import com.latiendita.paymentService.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MercadoPagoStatusMapperTest {

    @InjectMocks
    private MercadoPagoStatusMapper mapper;

    @Test
    void shouldMapApproved() {
        assertThat(mapper.toDomain("approved")).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void shouldMapEveryPendingLikeStatusToPending() {
        assertThat(mapper.toDomain("pending")).isEqualTo(PaymentStatus.PENDING);
        assertThat(mapper.toDomain("authorized")).isEqualTo(PaymentStatus.PENDING);
        assertThat(mapper.toDomain("in_process")).isEqualTo(PaymentStatus.PENDING);
        assertThat(mapper.toDomain("in_mediation")).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void shouldMapRejected() {
        assertThat(mapper.toDomain("rejected")).isEqualTo(PaymentStatus.REJECTED);
    }

    @Test
    void shouldMapCancelled() {
        assertThat(mapper.toDomain("cancelled")).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void shouldMapExpired() {
        assertThat(mapper.toDomain("expired")).isEqualTo(PaymentStatus.EXPIRED);
    }

    @Test
    void shouldMapUnknownOrNullToPending() {
        assertThat(mapper.toDomain("desconocido")).isEqualTo(PaymentStatus.PENDING);
        assertThat(mapper.toDomain(null)).isEqualTo(PaymentStatus.PENDING);
        assertThat(mapper.toDomain("  ")).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void shouldBeCaseInsensitive() {
        assertThat(mapper.toDomain("APPROVED")).isEqualTo(PaymentStatus.APPROVED);
        assertThat(mapper.toDomain("Rejected")).isEqualTo(PaymentStatus.REJECTED);
    }
}
