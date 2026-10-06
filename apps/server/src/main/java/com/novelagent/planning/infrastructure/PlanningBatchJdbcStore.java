package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.PlanningBatch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 规划批次存储。
 *
 * <p>将 JSON 来源快照与批次进度映射为领域记录。状态更新使用版本条件，锁定读取需在调用方事务中使用；这里不做模型生成或用户权限决策。</p>
 */
@Repository
public class PlanningBatchJdbcStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PlanningBatchJdbcStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 写入已由调用方校验的新持久化记录，保存来源及初始状态，不触发生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     * @param hash 幂等请求或来源内容的稳定指纹。
     */
    public PlanningBatch insert(UUID projectId, UUID requestId, PlanningBatch.Source source, String hash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planning_batch (id, project_id, request_id, source, source_hash)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?)
                """, id, projectId, requestId, json(source), hash);
        return find(projectId, id, false).orElseThrow();
    }

    /**
     * 按明确标识查询持久化记录，是否加锁由参数控制；查询范围不代替用户鉴权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param lock 是否加数据库行锁；加锁需要调用方维持有效事务。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<PlanningBatch> find(UUID projectId, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM planning_batch WHERE project_id = ? AND id = ?"
                + (lock ? " FOR UPDATE" : ""), this::read, projectId, id).stream().findFirst();
    }

    /**
     * 按项目与请求 ID 查询幂等创建记录，重复请求仍须核对内容一致。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<PlanningBatch> byRequest(UUID projectId, UUID requestId) {
        return jdbc.query("SELECT * FROM planning_batch WHERE project_id = ? AND request_id = ?",
                this::read, projectId, requestId).stream().findFirst();
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<PlanningBatch> list(UUID projectId) {
        return jdbc.query("SELECT * FROM planning_batch WHERE project_id = ? ORDER BY created_at DESC, id",
                this::read, projectId);
    }

    /**
     * 使用当前版本及状态条件执行状态更新，返回是否实际成功；旧快照不得覆盖新状态。
     *
     * @param batch 已读取的规划批次及预期版本。
     * @param status 要查询或迁移到的业务状态，不能跳过状态门禁。
     * @param checkpoints 当前批次有序的片段 ID 链。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     */
    public boolean transition(PlanningBatch batch, PlanningBatch.Status status, List<UUID> checkpoints, UUID outlineId) {
        return jdbc.update("""
                UPDATE planning_batch SET status = ?, checkpoint_ids = CAST(? AS jsonb), outline_version_id = ?,
                    row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE project_id = ? AND id = ? AND row_version = ? AND status = ?
                """, status.name(), json(checkpoints), outlineId, batch.projectId(), batch.id(),
                batch.version(), batch.status().name()) == 1;
    }

    /**
     * 从 SQL 行恢复批次来源快照、片段 ID 链、状态及大纲关联；JSON 损坏时抛出持久化错误，不回退为空批次。
     *
     * @param row 当前数据库结果行。
     * @param index JDBC 映射中的行序号，不是章节号。
     */
    private PlanningBatch read(ResultSet row, int index) throws SQLException {
        try {
            return new PlanningBatch(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                    row.getObject("request_id", UUID.class), mapper.readValue(row.getString("source"), PlanningBatch.Source.class),
                    row.getString("source_hash"), mapper.readValue(row.getString("checkpoint_ids"),
                            new TypeReference<List<UUID>>() { }), PlanningBatch.Status.valueOf(row.getString("status")),
                    row.getObject("outline_version_id", UUID.class), row.getLong("row_version"),
                    row.getTimestamp("created_at").toInstant());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划批次不能解析", exception);
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划批次不能序列化", exception);
        }
    }
}
