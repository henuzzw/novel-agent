package com.novelagent.canon.infrastructure;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 投影检查点查询。
 *
 * <p>查询事件是否发布及指定投影是否已记录检查点。仅返回现有进度，不负责发布、消费或修复投影。</p>
 */
@Repository
public class ProjectionStatusRepository {
    private final JdbcTemplate jdbc;

    public ProjectionStatusRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 检查当前正史版本关联 Outbox 是否已有发布标记，不读取 Kafka 来推测业务成功。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param canonVersion 有效正史版本水位，与记录行版本不同。
     */
    public boolean isPublished(UUID projectId, long canonVersion) {
        String sql = """
                SELECT count(*)
                  FROM outbox_event o
                 WHERE o.payload->>'projectId' = ?
                   AND (o.payload->>'canonVersion')::bigint = ?
                   AND o.published_at IS NOT NULL
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId.toString(), canonVersion);
        return count != null && count > 0;
    }

    /**
     * 检查指定投影类型是否已有事件检查点；没有检查点保持未完成，不伪造同步成功。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param canonVersion 有效正史版本水位，与记录行版本不同。
     * @param projectionType 投影类型，例如 PGVECTOR 或 NEO4J。
     */
    public boolean isProjected(UUID projectId, long canonVersion, String projectionType) {
        String sql = """
                SELECT count(*)
                  FROM outbox_event o
                 WHERE o.payload->>'projectId' = ?
                   AND (o.payload->>'canonVersion')::bigint = ?
                   AND EXISTS (
                       SELECT 1
                         FROM projection_checkpoint p
                        WHERE p.event_id = o.id
                          AND p.projection_type = ?
                   )
                """;
        Integer count = jdbc.queryForObject(
                sql, Integer.class, projectId.toString(), canonVersion, projectionType);
        return count != null && count > 0;
    }

}
