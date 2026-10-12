package com.latiendita.paymentService.payment;

public enum PaymentStatus {
    PENDING(0),
    REJECTED(1),
    CANCELLED(1),
    EXPIRED(1),
    APPROVED(2);

    private final int rank;

    PaymentStatus(int rank) {
        this.rank = rank;
    }

    public boolean canTransitionTo(PaymentStatus target) {
        return switch (this) {
            case PENDING -> true;
            case REJECTED, CANCELLED, EXPIRED -> target == APPROVED;
            case APPROVED -> false;
        };
    }

    public int rank() {
        return rank;
    }

    public boolean isFinal() {
        return this != PENDING;
    }
}
