package com.novelagent.canon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.OutboxEvent;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {
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
        for (OutboxEvent event : events.findTop20ByPublishedAtIsNullOrderByCreatedAtAsc()) {
            publish(event);
        }
    }

    private void publish(OutboxEvent event) {
        try {
            String payload = mapper.writeValueAsString(event.getPayload());
            kafka.send(event.getTopic(), event.getAggregateId().toString(), payload)
                    .get(10, TimeUnit.SECONDS);
            event.markPublished();
        } catch (Exception exception) {
            event.recordFailure();
            log.warn("Failed to publish outbox event {} to {}", event.getId(), event.getTopic(), exception);
        }
        events.save(event);
    }
}
