package com.novelagent.canon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
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
        OutboxEvent event = event(UUID.randomUUID());
        when(events.findPublishable(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(event));
        when(kafka.send(eq(event.getTopic()), eq(event.getAggregateId().toString()), any(String.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish();

        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(event.getAttempts()).isZero();
        assertThat(event.getNextAttemptAt()).isNull();
        verify(events).save(event);
    }

    @Test
    void recordsFailureWithBackoffWithoutMarkingEventPublished() {
        OutboxEvent event = event(UUID.randomUUID());
        when(events.findPublishable(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(event));
        when(kafka.send(eq(event.getTopic()), eq(event.getAggregateId().toString()), any(String.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Kafka unavailable")));

        publisher.publish();

        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(event.getLastError()).contains("Kafka unavailable");
        verify(events).save(event);
    }

    @Test
    void failedEventDefersLaterEventsOfSameAggregateButNotOthers() {
        UUID aggregate = UUID.randomUUID();
        OutboxEvent first = event(aggregate);
        OutboxEvent second = event(aggregate);
        OutboxEvent other = event(UUID.randomUUID());
        when(events.findPublishable(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(first, second, other));
        when(kafka.send(eq(first.getTopic()), eq(aggregate.toString()), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("rejected")));
        when(kafka.send(eq(other.getTopic()), eq(other.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish();

        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getAttempts()).isZero();
        assertThat(second.getPublishedAt()).isNull();
        assertThat(other.getPublishedAt()).isNotNull();
        verify(events, never()).save(second);
    }

    @Test
    void timeoutStopsTheBatch() {
        OutboxEvent first = event(UUID.randomUUID());
        OutboxEvent second = event(UUID.randomUUID());
        when(events.findPublishable(OutboxPublisher.BATCH_SIZE)).thenReturn(List.of(first, second));
        when(kafka.send(eq(first.getTopic()), eq(first.getAggregateId().toString()), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new TimeoutException("slow")));

        publisher.publish();

        assertThat(first.getAttempts()).isEqualTo(1);
        verify(kafka, never()).send(eq(second.getTopic()), eq(second.getAggregateId().toString()), anyString());
    }

    @Test
    void backoffGrowsAndIsCapped() {
        OutboxEvent event = event(UUID.randomUUID());
        Instant now = Instant.parse("2026-10-20T00:00:00Z");
        event.recordFailure(now, "x");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(3));
        event.recordFailure(now, "x");
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(6));
        for (int i = 0; i < 20; i++) event.recordFailure(now, "x".repeat(800));
        assertThat(event.getNextAttemptAt()).isEqualTo(now.plusSeconds(300));
        assertThat(event.getLastError()).hasSize(500);
    }

    private OutboxEvent event(UUID aggregateId) {
        ObjectMapper mapper = new ObjectMapper();
        return new OutboxEvent(UUID.randomUUID(), aggregateId, "CanonCommitted", "canon.topic",
                mapper.createObjectNode().put("canonVersion", 1));
    }
}
