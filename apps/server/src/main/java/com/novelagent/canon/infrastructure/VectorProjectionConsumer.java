package com.novelagent.canon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.tool.PgVectorSupport;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.memory.application.TextEmbeddingService;
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
            VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS public.vector), ?, ?)
            ON CONFLICT(source_type, source_id) DO UPDATE SET
                canon_version = EXCLUDED.canon_version,
                content = EXCLUDED.content,
                summary = EXCLUDED.summary,
                embedding = EXCLUDED.embedding,
                embedding_model = EXCLUDED.embedding_model,
                updated_at = EXCLUDED.updated_at
            """;

    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ManuscriptVersionRepository manuscripts;
    private final TextEmbeddingService embeddings;
    private final ProjectionCheckpointStore checkpoints;
    private final CharacterNameService characterNames;

    VectorProjectionConsumer(
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            ManuscriptVersionRepository manuscripts,
            TextEmbeddingService embeddings,
            ProjectionCheckpointStore checkpoints,
            CharacterNameService characterNames) {
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.manuscripts = manuscripts;
        this.embeddings = embeddings;
        this.checkpoints = checkpoints;
        this.characterNames = characterNames;
    }

    @KafkaListener(topics = "${app.kafka.canon-topic}", groupId = "novel-pgvector-projector-v2")
    @Transactional
    public void project(String message) throws Exception {
        CanonProjectionEvent event = CanonProjectionEvent.parse(message, mapper);
        if (checkpoints.isCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.PGVECTOR)) {
            return;
        }

        ManuscriptVersion manuscript = manuscripts.findById(event.manuscriptVersionId()).orElseThrow();
        String content = documentContent(manuscript);
        jdbc.update(
                UPSERT_DOCUMENT_SQL,
                documentId(manuscript.getId()),
                event.projectId(),
                "MANUSCRIPT",
                manuscript.getId(),
                event.canonVersion(),
                content,
                manuscript.getContent().summary(),
                PgVectorSupport.literal(embeddings.embed(content)),
                embeddings.modelName(),
                Timestamp.from(Instant.now()));

        checkpoints.markCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.PGVECTOR);
    }

    private UUID documentId(UUID manuscriptId) {
        return UUID.nameUUIDFromBytes(("manuscript:" + manuscriptId).getBytes(StandardCharsets.UTF_8));
    }

    private String documentContent(ManuscriptVersion manuscript) {
        var content = characterNames.render(manuscript.getProjectId(), manuscript.getContent());
        return String.join(
                "\n",
                content.title(),
                content.summary(),
                content.body());
    }
}
