package com.novelagent.canon.infrastructure;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;

class GraphProjectionConsumerTest {
    private CanonCommitRepository commits;
    private Neo4jClient neo4j;
    private JdbcTemplate jdbc;
    private ProjectionCheckpointStore checkpoints;
    private GraphProjectionConsumer consumer;

    @BeforeEach
    void setUp() {
        commits = mock(CanonCommitRepository.class);
        neo4j = mock(Neo4jClient.class, RETURNS_DEEP_STUBS);
        jdbc = mock(JdbcTemplate.class);
        checkpoints = mock(ProjectionCheckpointStore.class);
        consumer = new GraphProjectionConsumer(new ObjectMapper(), commits, jdbc, neo4j, checkpoints);
    }

    @Test
    void projectsEachAcceptedFactAndMarksCheckpoint() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        CanonCommit commit = new CanonCommit(commitId, UUID.randomUUID(), 1, UUID.randomUUID(),
                UUID.randomUUID(), 1, List.of(
                        fact("F1", "发现", "旧笔记"),
                        fact("F2", "位于", "雾港大学")));
        when(checkpoints.isCompleted(eventId, ProjectionCheckpointStore.ProjectionType.NEO4J)).thenReturn(false);
        when(commits.findById(commitId)).thenReturn(Optional.of(commit));

        consumer.project(message(eventId, commitId));

        verify(neo4j, times(3)).query(anyString());
        verify(checkpoints).markCompleted(eventId, ProjectionCheckpointStore.ProjectionType.NEO4J);
    }

    @Test
    void skipsAlreadyCompletedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID commitId = UUID.randomUUID();
        when(checkpoints.isCompleted(eventId, ProjectionCheckpointStore.ProjectionType.NEO4J)).thenReturn(true);

        consumer.project(message(eventId, commitId));

        verify(commits, never()).findById(commitId);
        verify(neo4j, never()).query(anyString());
    }

    private FactProposal fact(String id, String predicate, String object) {
        return new FactProposal(id, "EVENT", "顾弦", predicate, object, "正文证据", FactDecision.ACCEPTED);
    }

    private String message(UUID eventId, UUID commitId) {
        return """
                {"eventId":"%s","commitId":"%s","projectId":"%s","manuscriptVersionId":"%s","canonVersion":1}
                """.formatted(eventId, commitId, UUID.randomUUID(), UUID.randomUUID());
    }
}
