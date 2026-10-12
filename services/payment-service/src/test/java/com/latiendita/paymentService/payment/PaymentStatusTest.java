package com.latiendita.paymentService.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void pendingCanMoveToEveryStatus() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.REJECTED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.EXPIRED)).isTrue();
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isTrue();
    }

    @Test
    void rejectedCanOnlyBeRecoveredByApproval() {
        assertThat(PaymentStatus.REJECTED.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.REJECTED.canTransitionTo(PaymentStatus.CANCELLED)).isFalse();
        assertThat(PaymentStatus.REJECTED.canTransitionTo(PaymentStatus.EXPIRED)).isFalse();
        assertThat(PaymentStatus.REJECTED.canTransitionTo(PaymentStatus.REJECTED)).isFalse();
    }

    @Test
    void cancelledAndExpiredFollowTheSameRuleAsRejected() {
        assertThat(PaymentStatus.CANCELLED.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.EXPIRED.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
        assertThat(PaymentStatus.CANCELLED.canTransitionTo(PaymentStatus.PENDING)).isFalse();
        assertThat(PaymentStatus.EXPIRED.canTransitionTo(PaymentStatus.REJECTED)).isFalse();
    }

    @Test
    void approvedIsTerminalInThisVersion() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.APPROVED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    void rankOrdersStatesForOutOfOrderNotifications() {
        assertThat(PaymentStatus.PENDING.rank()).isZero();
        assertThat(PaymentStatus.REJECTED.rank()).isEqualTo(1);
        assertThat(PaymentStatus.CANCELLED.rank()).isEqualTo(1);
        assertThat(PaymentStatus.EXPIRED.rank()).isEqualTo(1);
        assertThat(PaymentStatus.APPROVED.rank()).isEqualTo(2);
    }

    @Test
    void isFinalOnlyForNonPending() {
        assertThat(PaymentStatus.PENDING.isFinal()).isFalse();
        assertThat(PaymentStatus.APPROVED.isFinal()).isTrue();
        assertThat(PaymentStatus.REJECTED.isFinal()).isTrue();
        assertThat(PaymentStatus.CANCELLED.isFinal()).isTrue();
        assertThat(PaymentStatus.EXPIRED.isFinal()).isTrue();
    }
}
