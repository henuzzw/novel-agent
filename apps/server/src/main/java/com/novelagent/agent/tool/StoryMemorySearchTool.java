package com.novelagent.agent.tool;

import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.TextEmbeddingService;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class StoryMemorySearchTool implements NovelReadTool {
    private final JdbcTemplate jdbc;
    private final TextEmbeddingService embeddings;

    StoryMemorySearchTool(JdbcTemplate jdbc, TextEmbeddingService embeddings) {
        this.jdbc = jdbc;
        this.embeddings = embeddings;
    }

    @Override
    public NovelToolName name() {
        return NovelToolName.SEARCH_STORY_MEMORY;
    }

    @Override
    public NovelToolResult execute(NovelToolRequest request) {
        String vector = PgVectorSupport.literal(embeddings.embed(request.query()));
        List<NovelMemoryContext.SemanticMemory> memories = jdbc.query("""
                SELECT m.chapter_number, d.canon_version,
                       1 - (d.embedding OPERATOR(public.<=>) CAST(? AS public.vector)) AS similarity,
                       d.summary, left(d.content, 4000) AS content
                  FROM semantic_document d
                  JOIN manuscript_version m ON m.id = d.source_id
                 WHERE d.project_id = ?
                   AND d.embedding IS NOT NULL
                   AND d.canon_version <= ?
                   AND m.chapter_number < ?
                 ORDER BY d.embedding OPERATOR(public.<=>) CAST(? AS public.vector)
                 LIMIT ?
                """, (result, row) -> new NovelMemoryContext.SemanticMemory(
                        result.getInt("chapter_number"),
                        result.getLong("canon_version"),
                        result.getDouble("similarity"),
                        result.getString("summary"),
                        result.getString("content")),
                vector,
                request.projectId(),
                request.canonVersion(),
                request.chapterNumber(),
                vector,
                request.semanticCandidateLimit());
        return NovelToolResult.semantic(memories);
    }
}
