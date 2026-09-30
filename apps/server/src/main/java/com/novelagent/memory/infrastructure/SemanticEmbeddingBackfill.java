package com.novelagent.memory.infrastructure;

import com.novelagent.agent.tool.PgVectorSupport;
import com.novelagent.memory.application.TextEmbeddingService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SemanticEmbeddingBackfill {
    private final JdbcTemplate jdbc;
    private final TextEmbeddingService embeddings;

    public SemanticEmbeddingBackfill(JdbcTemplate jdbc, TextEmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Scheduled(initialDelay = 1_000, fixedDelay = 60_000)
    @Transactional
    public void fillMissingEmbeddings() {
        var documents = jdbc.query("""
                SELECT id, content FROM semantic_document
                 WHERE embedding IS NULL OR embedding_model IS DISTINCT FROM ?
                 ORDER BY updated_at
                 LIMIT 25
                """, (rs, row) -> new Document(rs.getObject("id", UUID.class), rs.getString("content")),
                embeddings.modelName());
        for (Document document : documents) {
            String vector = PgVectorSupport.literal(embeddings.embed(document.content()));
            jdbc.update("""
                    UPDATE semantic_document
                       SET embedding = CAST(? AS public.vector), embedding_model = ?, updated_at = now()
                     WHERE id = ?
                    """, vector, embeddings.modelName(), document.id());
        }
    }

    private record Document(UUID id, String content) {
    }
}
