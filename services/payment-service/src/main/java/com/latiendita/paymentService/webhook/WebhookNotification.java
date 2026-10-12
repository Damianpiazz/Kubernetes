package com.latiendita.paymentService.webhook;

public record WebhookNotification(
        Long id,
        String action,
        String type,
        Data data) {

    public record Data(String id) {}
}
