package com.novelagent.canon.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.domain.OutboxEvent;
import com.novelagent.canon.infrastructure.OutboxEventRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CanonPublicationEvents {
    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;
    private final String topic;
    private final org.springframework.context.ApplicationEventPublisher publisher;
    public CanonPublicationEvents(OutboxEventRepository outbox, ObjectMapper mapper,
            @Value("${app.kafka.canon-topic}") String topic, org.springframework.context.ApplicationEventPublisher publisher) {
        this.outbox = outbox; this.mapper = mapper; this.topic = topic;
        this.publisher = publisher;
    }
    public void emit(CanonCommit commit) {
        emit(commit, commit.getCanonVersion());
    }
    public void emit(CanonCommit commit, long indexVersion) {
        UUID id = UUID.randomUUID();
        var payload = mapper.createObjectNode();
        payload.put("eventId", id.toString());
        payload.put("commitId", commit.getId().toString());
        payload.put("projectId", commit.getProjectId().toString());
        payload.put("chapterNumber", commit.getChapterNumber());
        payload.put("manuscriptVersionId", commit.getManuscriptVersionId().toString());
        payload.put("canonVersion", indexVersion);
        outbox.save(new OutboxEvent(id, commit.getProjectId(), "CanonCommitted", topic, payload));
        publisher.publishEvent(new com.novelagent.agent.application.AutomationPublicationListener.Published(
                commit.getProjectId(), commit.getChapterNumber()));
    }
}
