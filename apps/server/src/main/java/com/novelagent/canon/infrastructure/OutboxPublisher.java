package com.novelagent.canon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.OutboxEvent;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把 Outbox 事件发布到 Kafka（按单实例部署设计）。失败事件指数退避，不阻塞其他聚合；
 * 同一聚合内失败后，本批后续事件顺延，保持发布顺序。
 */
@Component
public class OutboxPublisher {
    static final int BATCH_SIZE = 20;
    private static final int WARN_ATTEMPTS = 10;
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository events;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;

    public OutboxPublisher(
            OutboxEventRepository events,
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper) {
        this.events = events;
        this.kafka = kafka;
        this.mapper = mapper;
    }

    @Scheduled(initialDelay = 2000, fixedDelay = 3000)
    @Transactional
    public void publish() {
        Set<UUID> blocked = new HashSet<>();
        for (OutboxEvent event : events.findPublishable(BATCH_SIZE)) {
            if (blocked.contains(event.getAggregateId())) continue;
            Outcome outcome = publish(event);
            if (outcome == Outcome.FAILED) blocked.add(event.getAggregateId());
            // Kafka 超时通常意味着整体不可达，本轮停止，避免逐条等待拖长事务。
            if (outcome == Outcome.TIMED_OUT) break;
        }
    }

    private Outcome publish(OutboxEvent event) {
        Outcome outcome;
        try {
            String payload = mapper.writeValueAsString(event.getPayload());
            kafka.send(event.getTopic(), event.getAggregateId().toString(), payload)
                    .get(10, TimeUnit.SECONDS);
            event.markPublished();
            outcome = Outcome.PUBLISHED;
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            event.recordFailure(Instant.now(), exception.getClass().getSimpleName() + ": " + exception.getMessage());
            outcome = isTimeout(exception) ? Outcome.TIMED_OUT : Outcome.FAILED;
            if (event.getAttempts() >= WARN_ATTEMPTS) {
                log.error("Outbox event {} to {} failed {} times; next attempt at {}", event.getId(),
                        event.getTopic(), event.getAttempts(), event.getNextAttemptAt(), exception);
            } else {
                log.warn("Failed to publish outbox event {} to {}", event.getId(), event.getTopic(), exception);
            }
        }
        events.save(event);
        return outcome;
    }

    private static boolean isTimeout(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException
                    || current instanceof org.apache.kafka.common.errors.TimeoutException) return true;
            if (current.getCause() == current) break;
        }
        return false;
    }

    private enum Outcome { PUBLISHED, FAILED, TIMED_OUT }
}
