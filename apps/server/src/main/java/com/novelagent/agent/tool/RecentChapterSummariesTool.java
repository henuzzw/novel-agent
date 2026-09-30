package com.novelagent.agent.tool;

import com.novelagent.memory.application.NovelMemoryContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class RecentChapterSummariesTool implements NovelReadTool {
    private final JdbcTemplate jdbc;

    RecentChapterSummariesTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public NovelToolName name() {
        return NovelToolName.GET_RECENT_CHAPTER_SUMMARIES;
    }

    @Override
    public NovelToolResult execute(NovelToolRequest request) {
        List<NovelMemoryContext.SemanticMemory> memories = jdbc.query("""
                SELECT m.chapter_number, d.canon_version, d.summary
                  FROM semantic_document d
                  JOIN manuscript_version m ON m.id = d.source_id
                 WHERE d.project_id = ?
                   AND d.source_type = 'MANUSCRIPT'
                   AND d.canon_version <= ?
                   AND m.chapter_number < ?
                 ORDER BY m.chapter_number DESC
                 LIMIT ?
                """, (result, row) -> new NovelMemoryContext.SemanticMemory(
                        result.getInt("chapter_number"),
                        result.getLong("canon_version"),
                        1.0,
                        result.getString("summary"),
                        ""),
                request.projectId(),
                request.canonVersion(),
                request.chapterNumber(),
                Math.min(3, request.semanticCandidateLimit()));
        return NovelToolResult.semantic(memories);
    }
}
