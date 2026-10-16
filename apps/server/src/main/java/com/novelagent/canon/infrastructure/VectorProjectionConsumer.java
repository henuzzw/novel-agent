package com.novelagent.canon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.memory.infrastructure.SemanticChunkStore;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class VectorProjectionConsumer {
    private static final String UPSERT_DOCUMENT_SQL = """
            INSERT INTO semantic_document(
                id, project_id, source_type, source_id, canon_version,
                content, summary, embedding, embedding_model, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?)
            ON CONFLICT(source_type, source_id) DO UPDATE SET
                canon_version = EXCLUDED.canon_version,
                content = EXCLUDED.content,
                summary = EXCLUDED.summary,
                embedding = CASE WHEN semantic_document.content = EXCLUDED.content THEN semantic_document.embedding ELSE NULL END,
                embedding_model = CASE WHEN semantic_document.content = EXCLUDED.content THEN semantic_document.embedding_model ELSE NULL END,
                chunked_content_hash = CASE WHEN semantic_document.content = EXCLUDED.content THEN semantic_document.chunked_content_hash ELSE NULL END,
                updated_at = EXCLUDED.updated_at
            """;

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ManuscriptVersionRepository manuscripts;
    private final SemanticChunkStore chunks;
    private final ProjectionCheckpointStore checkpoints;
    private final CharacterNameService characterNames;
    private final CanonCommitRepository commits;

    VectorProjectionConsumer(
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            ManuscriptVersionRepository manuscripts,
            SemanticChunkStore chunks,
            ProjectionCheckpointStore checkpoints,
            CharacterNameService characterNames, CanonCommitRepository commits) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.manuscripts = manuscripts;
        this.chunks = chunks;
        this.checkpoints = checkpoints;
        this.characterNames = characterNames;
        this.commits = commits;
    }

    @KafkaListener(topics = "${app.kafka.canon-topic}", groupId = "novel-pgvector-projector-v2")
    @Transactional
    public void project(String message) throws Exception {
        CanonProjectionEvent event = CanonProjectionEvent.parse(message, mapper);
        if (checkpoints.isCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.PGVECTOR)) {
            return;
        }

        var savedCommit = commits.findById(event.commitId());
        if (savedCommit.isEmpty() && Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM novel_project WHERE id = ?)", Boolean.class, event.projectId()))) return;
        var commit = savedCommit.orElseThrow();
        if (!commit.isActive()) {
            jdbc.update("DELETE FROM semantic_document WHERE source_type = 'MANUSCRIPT' AND source_id = ?",
                    event.manuscriptVersionId());
            checkpoints.markCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.PGVECTOR);
            return;
        }
        jdbc.update("""
                DELETE FROM semantic_document d USING canon_commit c
                 WHERE d.source_type = 'MANUSCRIPT' AND d.source_id = c.manuscript_version_id
                   AND c.project_id = ? AND c.chapter_number = ? AND c.active = FALSE
                """, event.projectId(), commit.getChapterNumber());
        ManuscriptVersion manuscript = manuscripts.findById(event.manuscriptVersionId()).orElseThrow();
        String content = documentContent(manuscript);
        jdbc.update(
                UPSERT_DOCUMENT_SQL,
                documentId(manuscript.getId()),
                event.projectId(),
                "MANUSCRIPT",
                manuscript.getId(),
                commit.getCanonVersion(),
                content,
                manuscript.getContent().summary(),
                Timestamp.from(Instant.now()));
        chunks.synchronize(documentId(manuscript.getId()));

        checkpoints.markCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.PGVECTOR);
    }

    private UUID documentId(UUID manuscriptId) {
        return UUID.nameUUIDFromBytes(("manuscript:" + manuscriptId).getBytes(StandardCharsets.UTF_8));
    }

    private String documentContent(ManuscriptVersion manuscript) {
        var content = characterNames.render(manuscript.getProjectId(), manuscript.getContent());
        return content.body();
    }
}
