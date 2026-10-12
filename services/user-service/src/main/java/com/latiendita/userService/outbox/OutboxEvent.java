package com.latiendita.userService.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String topic;

    @Column(nullable = false, length = 64)
    private String aggregateId;

    @Column(nullable = false, length = 30)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 500)
    private String lastError;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant sentAt;

    public static OutboxEvent create(String topic, Long aggregateId,
                                     String eventType, String payload) {
        OutboxEvent event = new OutboxEvent();
        event.topic = topic;
        event.aggregateId = String.valueOf(aggregateId);
        event.eventType = eventType;
        event.payload = payload;
        event.status = OutboxStatus.PENDING;
        event.attempts = 0;
        event.createdAt = Instant.now();
        return event;
    }

    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.status = OutboxStatus.FAILED;
        this.lastError = error == null ? null
                : (error.length() > 500 ? error.substring(0, 500) : error);
    }
}
