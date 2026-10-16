package com.novelagent.canon.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只读取当前有效发布且记忆已完成的正文，不以最新草稿替换已发布来源。 */
@Repository
public class PublishedManuscriptExportRepository {
    public record Chapter(int number, String content, UUID outlineId) { }
    private final JdbcTemplate jdbc;

    public PublishedManuscriptExportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Chapter> completedChapters(UUID projectId) {
        return jdbc.query("""
                SELECT c.chapter_number, m.content::text,
                       m.writing_basis->>'outlineId' AS outline_id
                FROM canon_commit c
                JOIN manuscript_version m ON m.id=c.manuscript_version_id
                    AND m.project_id=c.project_id AND m.chapter_number=c.chapter_number
                JOIN canon_memory_job j ON j.commit_id=c.id AND j.project_id=c.project_id
                WHERE c.project_id=? AND c.active AND m.status='AUTHOR_ACCEPTED'
                    AND j.status='SUCCEEDED'
                ORDER BY c.chapter_number
                """, (rs, row) -> new Chapter(rs.getInt(1), rs.getString(2),
                        rs.getString(3) == null ? null : UUID.fromString(rs.getString(3))), projectId);
    }
}
