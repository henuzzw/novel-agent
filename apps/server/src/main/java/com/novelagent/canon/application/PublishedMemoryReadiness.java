package com.novelagent.canon.application;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Guards new writing, not reading or reviewing existing prose. PostgreSQL is the memory authority. */
@Service
public class PublishedMemoryReadiness {
    private final JdbcTemplate jdbc;
    public PublishedMemoryReadiness(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireReady(UUID projectId, int chapter) {
        var incomplete = jdbc.query("""
                SELECT c.chapter_number, COALESCE(j.status, 'MISSING') AS status
                FROM canon_commit c LEFT JOIN canon_memory_job j ON j.commit_id=c.id
                WHERE c.project_id=? AND c.active AND c.chapter_number<?
                  AND (j.status IS NULL OR j.status NOT IN ('SUCCEEDED','SKIPPED'))
                ORDER BY c.chapter_number LIMIT 1
                """, (rs, row) -> "第" + rs.getInt(1) + "章记忆尚未落库成功（" + rs.getString(2) + "），请先处理记忆", projectId, chapter);
        if (!incomplete.isEmpty()) throw new IllegalStateException(incomplete.getFirst());
        var changed = jdbc.query("""
                SELECT MIN(source.chapter_number + 1)
                FROM canon_memory_history h JOIN canon_commit source ON source.id=h.commit_id AND source.active
                JOIN canon_commit later ON later.project_id=source.project_id AND later.active
                  AND later.chapter_number>source.chapter_number AND later.chapter_number<?
                  AND later.created_at<h.created_at
                WHERE h.project_id=? AND h.action IN ('EDIT','REGENERATE')
                """, (rs, row) -> rs.getObject(1, Integer.class), chapter, projectId);
        if (!changed.isEmpty() && changed.getFirst() != null)
            throw new IllegalStateException("前文记忆已修改，第" + changed.getFirst() + "章起的旧正文依赖需处理，暂不能继续生成");
    }
}
