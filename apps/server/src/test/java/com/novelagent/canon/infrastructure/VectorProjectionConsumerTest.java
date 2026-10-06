package com.novelagent.canon.infrastructure;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.memory.application.TextEmbeddingService;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class VectorProjectionConsumerTest {
    private JdbcTemplate jdbc;
    private ManuscriptVersionRepository manuscripts;
    private TextEmbeddingService embeddings;
    private ProjectionCheckpointStore checkpoints;
    private CharacterNameService characterNames;
    private VectorProjectionConsumer consumer;
    private CanonCommitRepository commits;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        manuscripts = mock(ManuscriptVersionRepository.class);
        embeddings = mock(TextEmbeddingService.class);
        checkpoints = mock(ProjectionCheckpointStore.class);
        characterNames = mock(CharacterNameService.class);
        commits = mock(CanonCommitRepository.class);
        consumer = new VectorProjectionConsumer(new ObjectMapper(), jdbc, manuscripts, embeddings, checkpoints,
                characterNames, commits);
    }

    @Test
    void projectsManuscriptAndMarksCheckpoint() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID manuscriptId = UUID.randomUUID();
        ManuscriptVersion manuscript = ManuscriptVersion.create(manuscriptId, UUID.randomUUID(), UUID.randomUUID(),
                1, 1, "LOCAL_TEMPLATE", null,
                new ManuscriptContent("第一章", "完整正文", "章节摘要", List.of()));
        when(checkpoints.isCompleted(eventId, ProjectionCheckpointStore.ProjectionType.PGVECTOR)).thenReturn(false);
        when(manuscripts.findById(manuscriptId)).thenReturn(Optional.of(manuscript));
        when(commits.findById(any(UUID.class))).thenReturn(Optional.of(new CanonCommit(UUID.randomUUID(),
                manuscript.getProjectId(), 1, manuscriptId, UUID.randomUUID(), 1, List.of())));
        when(characterNames.render(any(UUID.class), any(ManuscriptContent.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(embeddings.embed(anyString())).thenReturn(new float[] {0.1f, 0.2f});
        when(embeddings.modelName()).thenReturn("test-embedding");

        consumer.project(message(eventId, manuscriptId));

        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
        verify(checkpoints).markCompleted(eventId, ProjectionCheckpointStore.ProjectionType.PGVECTOR);
    }

    @Test
    void skipsAlreadyCompletedEvent() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID manuscriptId = UUID.randomUUID();
        when(checkpoints.isCompleted(eventId, ProjectionCheckpointStore.ProjectionType.PGVECTOR)).thenReturn(true);

        consumer.project(message(eventId, manuscriptId));

        verify(manuscripts, never()).findById(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void removesRetiredManuscriptWithoutEmbeddingItAgain() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID manuscriptId = UUID.randomUUID();
        CanonCommit retired = new CanonCommit(UUID.randomUUID(), UUID.randomUUID(), 1, manuscriptId,
                UUID.randomUUID(), 1, List.of());
        retired.supersede(UUID.randomUUID());
        when(commits.findById(any(UUID.class))).thenReturn(Optional.of(retired));

        consumer.project(message(eventId, manuscriptId));

        verify(jdbc).update(anyString(), any(Object[].class));
        verify(manuscripts, never()).findById(any());
        verify(embeddings, never()).embed(anyString());
        verify(checkpoints).markCompleted(eventId, ProjectionCheckpointStore.ProjectionType.PGVECTOR);
    }

    private String message(UUID eventId, UUID manuscriptId) {
        return """
                {"eventId":"%s","commitId":"%s","projectId":"%s","manuscriptVersionId":"%s","canonVersion":1}
                """.formatted(eventId, UUID.randomUUID(), UUID.randomUUID(), manuscriptId);
    }
}
