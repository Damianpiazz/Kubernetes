package com.latiendita.paymentService.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${outbox.relay.batch-size:50}")
    private int batchSize;

    @Value("${outbox.relay.max-attempts:10}")
    private int maxAttempts;

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = repository.findNextBatch(batchSize, maxAttempts);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(5, TimeUnit.SECONDS);
                event.markSent();
                log.info("Outbox publicado: {} id={} topic={}",
                        event.getEventType(), event.getId(), event.getTopic());
            } catch (Exception ex) {
                event.markFailed(ex.getMessage());
                log.warn("Outbox fallo: {} id={} intento={}/{}: {}",
                        event.getEventType(), event.getId(), event.getAttempts(),
                        maxAttempts, ex.getMessage());
            }
        }
    }
}
