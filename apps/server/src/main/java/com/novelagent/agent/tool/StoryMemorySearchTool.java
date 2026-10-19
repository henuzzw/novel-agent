package com.novelagent.agent.tool;

import com.novelagent.memory.application.MemorySearchTerms;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.TextEmbeddingService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class StoryMemorySearchTool implements NovelReadTool {
    private static final Logger log = LoggerFactory.getLogger(StoryMemorySearchTool.class);
    private static final String SELECT = """
            SELECT s.id, s.content, s.start_offset, s.end_offset, d.source_id, d.source_type,
                   d.canon_version, d.project_id, m.chapter_number
              FROM semantic_document_chunk s JOIN semantic_document d ON d.id = s.document_id
              LEFT JOIN manuscript_version m ON d.source_type = 'MANUSCRIPT' AND m.id = d.source_id AND m.project_id = d.project_id
              LEFT JOIN canon_commit c ON c.manuscript_version_id = m.id AND c.project_id = d.project_id AND c.active = TRUE
              LEFT JOIN story_bible_version b ON d.source_type = 'PLANNING_NOTES' AND b.id = d.source_id AND b.project_id = d.project_id
              JOIN novel_project p ON p.id = d.project_id
             WHERE d.project_id = ? AND d.chunked_content_hash IS NOT NULL AND (
                (d.source_type = 'MANUSCRIPT' AND c.canon_version = d.canon_version AND c.canon_version <= ? AND m.chapter_number < ?)
                OR (d.source_type = 'PLANNING_NOTES' AND b.id = ? AND b.id = p.current_bible_version_id AND b.status = 'PUBLISHED'
                    AND d.content = b.content->>'developmentNotes')
             )
            """;
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
        if (request.semanticCandidateLimit() <= 0) return NovelToolResult.semantic(List.of());
        List<Hit> lexical = keywordSearch(request);
        List<Hit> semantic = List.of();
        if (request.query() != null && !request.query().isBlank()) {
            try {
                String query = request.query().substring(0, Math.min(2000, request.query().length()));
                String vector = PgVectorSupport.literal(embeddings.embed(query));
                var args = arguments(request);
                args.add(embeddings.modelName()); args.add(vector); args.add(request.semanticCandidateLimit());
                semantic = jdbc.query(SELECT + " AND s.embedding IS NOT NULL AND s.embedding_model = ?"
                        + " ORDER BY s.embedding OPERATOR(public.<=>) CAST(? AS public.vector), s.id LIMIT ?",
                        StoryMemorySearchTool::hit, args.toArray());
            } catch (RuntimeException failure) {
                log.warn("Semantic retrieval unavailable for project {} ({}); using keyword results",
                        request.projectId(), failure.getClass().getSimpleName());
            }
        }
        return NovelToolResult.semantic(fuse(lexical, semantic, request.semanticCandidateLimit()));
    }

    private List<Hit> keywordSearch(NovelToolRequest request) {
        List<String> terms = MemorySearchTerms.keywords(request.query(), request.keywords());
        if (terms.isEmpty()) return List.of();
        var args = arguments(request);
        String score = String.join(" + ", terms.stream()
                .map(term -> "CASE WHEN position(lower(?) in lower(s.content)) > 0 THEN 1 ELSE 0 END").toList());
        args.addAll(terms);
        args.addAll(terms);
        args.add(request.semanticCandidateLimit());
        return jdbc.query(SELECT + " AND (" + score + ") > 0 ORDER BY (" + score + ") DESC, s.id LIMIT ?",
                StoryMemorySearchTool::hit, args.toArray());
    }

    private List<Object> arguments(NovelToolRequest request) {
        List<Object> result = new ArrayList<>();
        result.add(request.projectId()); result.add(request.canonVersion()); result.add(request.chapterNumber());
        result.add(request.planningVersionId());
        return result;
    }

    private static Hit hit(ResultSet rs, int row) throws SQLException {
        boolean planning = "PLANNING_NOTES".equals(rs.getString("source_type"));
        String provenance = "来源项目=" + rs.getObject("project_id") + "；资料类型=" + rs.getString("source_type")
                + "；来源版本=" + rs.getObject("source_id")
                + "；原文字符范围=[" + rs.getInt("start_offset") + "," + rs.getInt("end_offset") + ")";
        return new Hit(rs.getObject("id", UUID.class), planning ? 0 : rs.getInt("chapter_number"),
                planning ? NovelMemoryContext.AUTHOR_PLANNING : rs.getLong("canon_version"), provenance, rs.getString("content"));
    }

    /** Reciprocal ranks combine independent lexical/vector orderings without comparing incompatible scores. */
    static List<NovelMemoryContext.SemanticMemory> fuse(List<Hit> lexical, List<Hit> semantic, int limit) {
        Map<UUID, Hit> hits = new LinkedHashMap<>();
        Map<UUID, Double> scores = new LinkedHashMap<>();
        for (List<Hit> ranking : List.of(lexical, semantic)) {
            for (int i = 0; i < ranking.size(); i++) {
                Hit hit = ranking.get(i);
                hits.putIfAbsent(hit.id(), hit);
                scores.merge(hit.id(), 1.0 / (60 + i + 1), Double::sum);
            }
        }
        return scores.entrySet().stream().sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(limit).map(entry -> {
                    Hit hit = hits.get(entry.getKey());
                    return new NovelMemoryContext.SemanticMemory(hit.chapter(), hit.canon(), entry.getValue(),
                            hit.provenance(), hit.content(), false, null, null, hit.id().toString());
                }).toList();
    }

    record Hit(UUID id, int chapter, long canon, String provenance, String content) { }
}
