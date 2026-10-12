package com.novelagent.memory.infrastructure;

import com.novelagent.agent.tool.PgVectorSupport;
import com.novelagent.memory.application.TextEmbeddingService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class SemanticEmbeddingBackfill {
    private static final Logger log = LoggerFactory.getLogger(SemanticEmbeddingBackfill.class);
    private final JdbcTemplate jdbc;
    private final TextEmbeddingService embeddings;
    private final SemanticChunkStore chunks;

    public SemanticEmbeddingBackfill(JdbcTemplate jdbc, TextEmbeddingService embeddings, SemanticChunkStore chunks) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
        this.chunks = chunks;
    }

    @Scheduled(initialDelay = 1_000, fixedDelay = 60_000)
    public void fillMissingEmbeddings() {
        // Only the effective published planning snapshot enters the planning pool, never an unfinished run.
        jdbc.update("""
                INSERT INTO semantic_document(id, project_id, source_type, source_id, canon_version, content, summary, updated_at)
                SELECT b.id, b.project_id, 'PLANNING_NOTES', b.id, 0, b.content->>'developmentNotes', '已确认规划底稿', now()
                  FROM novel_project p
                  JOIN outline_version o ON o.id = p.current_outline_version_id AND o.project_id = p.id AND o.status = 'PUBLISHED'
                  JOIN story_bible_version b ON b.id = o.source_bible_version_id AND b.project_id = p.id AND b.status = 'PUBLISHED'
                 WHERE b.id = p.current_bible_version_id AND length(COALESCE(b.content->>'developmentNotes', '')) > 0
                ON CONFLICT(source_type, source_id) DO UPDATE SET
                    content = EXCLUDED.content, chunked_content_hash = NULL, updated_at = now()
                WHERE semantic_document.content IS DISTINCT FROM EXCLUDED.content
                """);
        var pending = jdbc.query("""
                SELECT id FROM semantic_document WHERE chunked_content_hash IS NULL ORDER BY updated_at LIMIT 5
                """, (rs, row) -> rs.getObject("id", UUID.class));
        pending.forEach(chunks::synchronize);
        var documents = jdbc.query("""
                SELECT s.id, s.content FROM semantic_document_chunk s
                  JOIN semantic_document d ON d.id = s.document_id
                 WHERE (s.embedding IS NULL OR s.embedding_model IS DISTINCT FROM ?)
                   AND d.chunked_content_hash IS NOT NULL AND (
                       (d.source_type = 'MANUSCRIPT' AND EXISTS (
                           SELECT 1 FROM canon_commit c WHERE c.manuscript_version_id = d.source_id
                             AND c.project_id = d.project_id AND c.active = TRUE AND c.canon_version = d.canon_version))
                       OR (d.source_type = 'PLANNING_NOTES' AND EXISTS (
                           SELECT 1 FROM novel_project p JOIN story_bible_version b ON b.id = p.current_bible_version_id
                            WHERE p.id = d.project_id AND b.id = d.source_id AND b.project_id = p.id
                              AND b.status = 'PUBLISHED' AND d.content = b.content->>'developmentNotes'))
                   )
                 ORDER BY s.updated_at, s.id
                 LIMIT 8
                """, (rs, row) -> new Document(rs.getObject("id", UUID.class), rs.getString("content")),
                embeddings.modelName());
        for (Document document : documents) {
            try {
                String vector = PgVectorSupport.literal(embeddings.embed(document.content()));
                jdbc.update("""
                        UPDATE semantic_document_chunk
                           SET embedding = CAST(? AS public.vector), embedding_model = ?, updated_at = now()
                         WHERE id = ? AND content = ?
                        """, vector, embeddings.modelName(), document.id(), document.content());
            } catch (RuntimeException failure) {
                log.warn("Paragraph embedding unavailable ({}); keyword retrieval remains enabled",
                        failure.getClass().getSimpleName());
                break;
            }
        }
    }

    private record Document(UUID id, String content) {
    }
}
