package com.novelagent.canon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {
    private OutboxEventRepository events;
    private KafkaTemplate<String, String> kafka;
    private OutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        events = mock(OutboxEventRepository.class);
        kafka = mock(KafkaTemplate.class);
        publisher = new OutboxPublisher(events, kafka, new ObjectMapper());
    }

    @Test
    void marksEventPublishedOnlyAfterKafkaAcknowledges() {
        OutboxEvent event = event();
        when(events.findTop20ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafka.send(eq(event.getTopic()), eq(event.getAggregateId().toString()), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        verify(events).save(event);
    }

    @Test
    void recordsFailureWithoutMarkingEventPublished() {
        OutboxEvent event = event();
        when(events.findTop20ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafka.send(eq(event.getTopic()), eq(event.getAggregateId().toString()), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publish();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        verify(events).save(event);
    }

    private OutboxEvent event() {
        ObjectMapper mapper = new ObjectMapper();
        return new OutboxEvent(UUID.randomUUID(), UUID.randomUUID(), "CanonCommitted", "canon.topic",
                mapper.createObjectNode().put("canonVersion", 1));
    }
}
