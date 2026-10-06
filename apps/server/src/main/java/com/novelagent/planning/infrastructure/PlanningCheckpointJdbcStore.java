package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.PlanningCheckpoint;
import com.novelagent.planning.domain.PlanningCheckpointResult;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 规划片段存储。
 *
 * <p>映射冻结来源、前置依赖及生成结果，并通过版本条件更新状态。空结果与空依赖各有业务含义，不能将缺失数据当成成功生成。</p>
 */
@Repository
public class PlanningCheckpointJdbcStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PlanningCheckpointJdbcStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 写入已由调用方校验的新持久化记录，保存来源及初始状态，不触发生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param key 规划片段稳定键，复用还必须匹配来源指纹。
     * @param from 范围起始章号。
     * @param to 范围结束章号。
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     */
    public PlanningCheckpoint insert(UUID projectId, String key, int from, int to, PlanningCheckpoint.Source source) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO planning_checkpoint
                    (id, project_id, chunk_key, chapter_from, chapter_to, source_bible_version_id,
                     source_bible_row_version, creative_strategy, policy_version, provider, author_instruction, dependency_hash,
                     dependencies, schema_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, id, projectId, key, from, to, source.bibleId(), source.bibleRowVersion(),
                source.creativeStrategy().strategy().name(), source.creativeStrategy().policyVersion(),
                source.provider().name(), source.instruction(), source.dependencyHash(),
                json(source.dependencies()), PlanningCheckpoint.SCHEMA_VERSION);
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
    public Optional<PlanningCheckpoint> find(UUID projectId, UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM planning_checkpoint WHERE project_id = ? AND id = ?"
                + (lock ? " FOR UPDATE" : ""), this::read, projectId, id).stream().findFirst();
    }

    /**
     * 按项目、片段键和依赖指纹精确查询可复用片段，不能按标题或范围近似匹配。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param key 规划片段稳定键，复用还必须匹配来源指纹。
     * @param dependencyHash 规划片段的冻结依据与依赖指纹。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<PlanningCheckpoint> matching(UUID projectId, String key, String dependencyHash) {
        return jdbc.query("SELECT * FROM planning_checkpoint WHERE project_id = ? AND chunk_key = ? AND dependency_hash = ?",
                this::read, projectId, key, dependencyHash).stream().findFirst();
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<PlanningCheckpoint> list(UUID projectId) {
        return jdbc.query("SELECT * FROM planning_checkpoint WHERE project_id = ? ORDER BY created_at DESC, id",
                this::read, projectId);
    }

    /**
     * 使用当前版本及状态条件执行状态更新，返回是否实际成功；旧快照不得覆盖新状态。
     *
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param status 要查询或迁移到的业务状态，不能跳过状态门禁。
     * @param attempt 当前执行尝试编号，隔离取消或重试后的旧执行器。
     * @param result 本次操作或模型调用产生的结果。
     * @param failure 本次失败原因或来源冲突信息。
     */
    public boolean transition(PlanningCheckpoint expected, PlanningCheckpoint.Status status, long attempt,
            PlanningCheckpointResult result, String failure) {
        return jdbc.update("""
                UPDATE planning_checkpoint
                   SET status = ?, attempt = ?, result = CAST(? AS jsonb), failure = ?,
                       row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE project_id = ? AND id = ? AND status = ? AND attempt = ? AND row_version = ?
                """, status.name(), attempt, result == null ? null : json(result), failure,
                expected.projectId(), expected.id(), expected.status().name(), expected.attempt(), expected.version()) == 1;
    }

    /**
     * 映射片段行、策略版本、依赖链及可空结果，attempt 与 row_version 分别保留；不存在结果不表示空章成功。
     *
     * @param row 当前数据库结果行。
     * @param index JDBC 映射中的行序号，不是章节号。
     */
    private PlanningCheckpoint read(ResultSet row, int index) throws SQLException {
        var source = new PlanningCheckpoint.Source(row.getObject("source_bible_version_id", UUID.class),
                row.getLong("source_bible_row_version"), new CreativeStrategyPolicy(
                CreativeStrategy.valueOf(row.getString("creative_strategy")), row.getInt("policy_version")),
                ModelProvider.valueOf(row.getString("provider")), row.getString("author_instruction"),
                row.getString("dependency_hash"), dependencies(row.getString("dependencies")));
        String result = row.getString("result");
        return new PlanningCheckpoint(row.getObject("id", UUID.class), row.getObject("project_id", UUID.class),
                row.getString("chunk_key"), row.getInt("chapter_from"), row.getInt("chapter_to"), source,
                PlanningCheckpoint.Status.valueOf(row.getString("status")), row.getLong("attempt"), row.getLong("row_version"),
                result == null ? null : result(result), row.getString("failure"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }

    private String json(Object result) {
        try { return mapper.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("规划分块不能序列化", exception); }
    }

    /**
     * 反序列化明确依赖数组，损坏 JSON 抛出错误，不猜测或清空依赖以继续执行。
     *
     * @param json 存储中的 JSON 文本，损坏时拒绝恢复为有效对象。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    private List<PlanningCheckpoint.Dependency> dependencies(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<PlanningCheckpoint.Dependency>>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划依赖不能解析", exception);
        }
    }

    /**
     * 反序列化已经保存的片段结果；存储 JSON 格式错误时拒绝恢复，不制造成功结果。
     *
     * @param json 存储中的 JSON 文本，损坏时拒绝恢复为有效对象。
     */
    private PlanningCheckpointResult result(String json) {
        try { return mapper.readValue(json, PlanningCheckpointResult.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("规划分块不能解析", exception); }
    }
}
