package com.novelagent.agent.tool;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelagent.memory.application.TextEmbeddingService;
import com.novelagent.memory.infrastructure.LocalFeatureHashEmbeddingService;
import com.novelagent.memory.infrastructure.SemanticChunkStore;
import com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Small isolated-schema verification of real PostgreSQL operators, source filters and V058. */
@DatabaseTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParagraphMemoryDatabaseTest {
    private final String schema = "paragraph_memory_test_" + UUID.randomUUID().toString().replace("-", "");
    private JdbcTemplate jdbc;
    private SemanticChunkStore chunks;
    private TransactionTemplate tx;

    @BeforeAll void setup() throws Exception {
        var source = new DriverManagerDataSource(TestDatabase.url() + "?currentSchema=" + schema + "&sslmode=" + env("DB_SSL_MODE", "disable"),
                env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", "change-me"));
        jdbc = new JdbcTemplate(source);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        jdbc.execute("CREATE SCHEMA " + schema);
        jdbc.execute("""
                CREATE TABLE novel_project(id uuid PRIMARY KEY, current_bible_version_id uuid, current_outline_version_id uuid);
                CREATE TABLE story_bible_version(id uuid PRIMARY KEY, project_id uuid, status text, content jsonb);
                CREATE TABLE outline_version(id uuid PRIMARY KEY, project_id uuid, status text, source_bible_version_id uuid);
                CREATE TABLE manuscript_version(id uuid PRIMARY KEY, project_id uuid, chapter_number integer);
                CREATE TABLE canon_commit(manuscript_version_id uuid, project_id uuid, chapter_number integer, canon_version bigint, active boolean);
                CREATE TABLE semantic_document(id uuid PRIMARY KEY, project_id uuid, source_type text, source_id uuid,
                    canon_version bigint, content text NOT NULL, summary text, updated_at timestamptz DEFAULT now(),
                    UNIQUE(source_type, source_id));
                """);
        try (var stream = getClass().getResourceAsStream("/db/migration/V058__paragraph_memory.sql")) {
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        chunks = new SemanticChunkStore(jdbc);
    }

    @Test void retrievesActualLateParagraphAndFiltersOtherProjectsFutureRetiredAndHigherCanon() {
        UUID project = project();
        String body = "无关开头。\n".repeat(800) + "他把那张纸条交给班主任，收件人没有变。";
        UUID document = manuscript(project, 1, 1, true, body);
        manuscript(project, 20, 2, true, "未来章的纸条");
        manuscript(project, 2, 99, true, "未来正史的纸条");
        manuscript(project, 2, 2, false, "撤回的纸条");
        manuscript(project(), 1, 1, true, "别的项目的纸条");
        var failing = mock(TextEmbeddingService.class);
        when(failing.embed(anyString())).thenThrow(new IllegalStateException("offline"));
        var tool = new StoryMemorySearchTool(jdbc, failing);
        var result = tool.execute(new NovelToolRequest(project, 10, 10, "纸条", 10, 10));
        assertThat(result.semanticMemories()).hasSize(1);
        var hit = result.semanticMemories().getFirst();
        assertThat(hit.content()).contains("交给班主任");
        assertThat(hit.summary()).contains(project.toString(), "原文字符范围");
        assertThat(hit.chapterNumber()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT start_offset FROM semantic_document_chunk WHERE id=?", Integer.class,
                UUID.fromString(hit.sourceKey()))).isGreaterThan(4000);
        int before = jdbc.queryForObject("SELECT count(*) FROM semantic_document_chunk WHERE document_id=?", Integer.class, document);
        tx.executeWithoutResult(status -> chunks.synchronize(document));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM semantic_document_chunk WHERE document_id=?", Integer.class, document)).isEqualTo(before);
    }

    @Test void planningOnlyUsesCurrentPublishedExactSnapshotAndKeepsItOutOfCanon() {
        UUID project = project(), bible = UUID.randomUUID(), outline = UUID.randomUUID();
        jdbc.update("INSERT INTO story_bible_version VALUES (?, ?, 'PUBLISHED', CAST(? AS jsonb))", bible, project,
                "{\"developmentNotes\":\"纸条上的秘密在结尾才揭示。\"}");
        jdbc.update("INSERT INTO outline_version VALUES (?, ?, 'PUBLISHED', ?)", outline, project, bible);
        jdbc.update("UPDATE novel_project SET current_bible_version_id=?, current_outline_version_id=? WHERE id=?", bible, outline, project);
        var embeddings = new LocalFeatureHashEmbeddingService();
        new SemanticEmbeddingBackfill(jdbc, embeddings, chunks).fillMissingEmbeddings();
        var tool = new StoryMemorySearchTool(jdbc, embeddings);
        var request = new NovelToolRequest(project, 1, 0, "纸条", 10, 10, bible, List.of("纸条"));
        var result = tool.execute(request);
        assertThat(result.semanticMemories()).hasSize(1).allMatch(value -> value.authorPlanning());
        assertThat(result.semanticMemories().getFirst().content()).contains("结尾才揭示");
        assertThat(tool.execute(new NovelToolRequest(project, 1, 0, "纸条", 10, 10, UUID.randomUUID(), List.of())).semanticMemories()).isEmpty();
        jdbc.update("UPDATE story_bible_version SET content=CAST(? AS jsonb) WHERE id=?", "{\"developmentNotes\":\"作者已改掉旧秘密\"}", bible);
        assertThat(tool.execute(request).semanticMemories()).isEmpty();
        new SemanticEmbeddingBackfill(jdbc, embeddings, chunks).fillMissingEmbeddings();
        assertThat(tool.execute(new NovelToolRequest(project, 1, 0, "旧秘密", 10, 10, bible, List.of())).semanticMemories())
                .allMatch(value -> value.content().contains("作者已改掉旧秘密"));
    }

    @Test void vectorSearchRejectsDifferentEmbeddingModelAndFusionPreservesMultipleChunks() {
        UUID project = project();
        UUID valid = manuscript(project, 1, 1, true, "短篇里的旧约定");
        UUID wrong = manuscript(project, 2, 2, true, "不同模型的旧材料");
        var embeddings = new LocalFeatureHashEmbeddingService();
        String vector = PgVectorSupport.literal(embeddings.embed("旧材料"));
        jdbc.update("UPDATE semantic_document_chunk SET embedding=CAST(? AS public.vector), embedding_model=? WHERE document_id=?",
                vector, embeddings.modelName(), valid);
        jdbc.update("UPDATE semantic_document_chunk SET embedding=CAST(? AS public.vector), embedding_model='wrong-model' WHERE document_id=?", vector, wrong);
        var result = new StoryMemorySearchTool(jdbc, embeddings).execute(new NovelToolRequest(project, 3, 2, "xyz_unknown", 10, 10));
        assertThat(result.semanticMemories()).hasSize(1);
        assertThat(result.semanticMemories().getFirst().chapterNumber()).isEqualTo(1);
        var a = new StoryMemorySearchTool.Hit(UUID.randomUUID(), 1, 1, "来源A", "段落A");
        var b = new StoryMemorySearchTool.Hit(UUID.randomUUID(), 1, 1, "来源B", "段落B");
        assertThat(StoryMemorySearchTool.fuse(List.of(a, b), List.of(b, a), 10)).hasSize(2);
        assertThat(StoryMemorySearchTool.fuse(List.of(a), List.of(a), 10)).hasSize(1);
    }

    private UUID project() {
        UUID id = UUID.randomUUID(); jdbc.update("INSERT INTO novel_project(id) VALUES (?)", id); return id;
    }

    private UUID manuscript(UUID project, int chapter, long canon, boolean active, String body) {
        UUID source = UUID.randomUUID(), document = UUID.randomUUID();
        jdbc.update("INSERT INTO manuscript_version VALUES (?, ?, ?)", source, project, chapter);
        jdbc.update("INSERT INTO canon_commit VALUES (?, ?, ?, ?, ?)", source, project, chapter, canon, active);
        jdbc.update("INSERT INTO semantic_document(id,project_id,source_type,source_id,canon_version,content,summary) VALUES (?,?,'MANUSCRIPT',?,?,?,?)",
                document, project, source, canon, body, "摘要");
        tx.executeWithoutResult(status -> chunks.synchronize(document)); return document;
    }

    @AfterAll void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
}
