package com.novelagent.canon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 只在单连接临时表验证真实PostgreSQL筛选，不启动应用或写入小说业务表。 */
@EnabledIfEnvironmentVariable(named = "NOVEL_EXPORT_DB_TEST", matches = "true")
class PublishedManuscriptExportRepositoryTest {
    @Test void selectsOnlyActiveAcceptedSourceWithFullyCompletedMemoryInChapterOrder() throws Exception {
        String url = "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432") + "/" + env("DB_NAME", "novel_agent");
        try (var connection = DriverManager.getConnection(url, env("DB_USERNAME", "novel_app"), env("DB_PASSWORD", ""))) {
            connection.setAutoCommit(false);
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            // Session-local tables shadow business tables without touching their data.
            jdbc.execute("CREATE TEMP TABLE canon_commit(id uuid, project_id uuid, chapter_number int, manuscript_version_id uuid, active boolean)");
            jdbc.execute("CREATE TEMP TABLE manuscript_version(id uuid, project_id uuid, chapter_number int, status text, content jsonb, writing_basis jsonb)");
            jdbc.execute("CREATE TEMP TABLE canon_memory_job(commit_id uuid, project_id uuid, status text)");
            UUID project = UUID.randomUUID(), other = UUID.randomUUID(), outline = UUID.randomUUID();
            for (int chapter = 8; chapter >= 1; chapter--) {
                String state = List.of("SUCCEEDED", "RUNNING", "SUCCEEDED", "NEEDS_CONFIRMATION", "FAILED", "SKIPPED", "PENDING", "SUPERSEDED").get(chapter - 1);
                insert(jdbc, project, chapter, true, "AUTHOR_ACCEPTED", state, outline, "正文" + chapter);
            }
            insert(jdbc, project, 1, false, "AUTHOR_ACCEPTED", "SUCCEEDED", outline, "已替换旧稿");
            insert(jdbc, project, 9, true, "DRAFT", "SUCCEEDED", outline, "草稿");
            insert(jdbc, other, 10, true, "AUTHOR_ACCEPTED", "SUCCEEDED", outline, "其他项目");
            insert(jdbc, project, 11, true, "AUTHOR_ACCEPTED", null, outline, "未整理记忆");
            // A newer unreferenced draft must never replace the published manuscript in the export.
            jdbc.update("INSERT INTO manuscript_version VALUES (?,?,1,'DRAFT','{\"body\":\"新版草稿\"}',null)", UUID.randomUUID(), project);
            var result = new PublishedManuscriptExportRepository(jdbc).completedChapters(project);
            assertThat(result).extracting(PublishedManuscriptExportRepository.Chapter::number).containsExactly(1, 3);
            assertThat(result).extracting(PublishedManuscriptExportRepository.Chapter::outlineId).containsOnly(outline);
            assertThat(result).allSatisfy(chapter -> assertThat(chapter.content()).contains("正文").doesNotContain("草稿", "旧稿"));
            connection.rollback();
        }
    }

    private void insert(JdbcTemplate jdbc, UUID project, int chapter, boolean active, String manuscriptState,
            String memoryState, UUID outline, String body) {
        UUID commit = UUID.randomUUID(), manuscript = UUID.randomUUID();
        jdbc.update("INSERT INTO manuscript_version VALUES (?,?,?,?,?::jsonb,?::jsonb)", manuscript, project, chapter,
                manuscriptState, "{\"body\":\"" + body + "\"}", "{\"outlineId\":\"" + outline + "\"}");
        jdbc.update("INSERT INTO canon_commit VALUES (?,?,?,?,?)", commit, project, chapter, manuscript, active);
        if (memoryState != null) jdbc.update("INSERT INTO canon_memory_job VALUES (?,?,?)", commit, project, memoryState);
    }
    private static String env(String key, String fallback) { return System.getenv().getOrDefault(key, fallback); }
}
