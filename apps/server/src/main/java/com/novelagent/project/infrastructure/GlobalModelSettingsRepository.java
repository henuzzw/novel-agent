package com.novelagent.project.infrastructure;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 全局模型配置存储。
 *
 * <p>使用 JDBC 映射用户模型设置；初次写入以冲突忽略防止重复创建，后续更新要求 row_version 匹配。save 返回是否实际写入，由服务转为版本冲突错误。</p>
 */
@Repository
public class GlobalModelSettingsRepository {
    private final JdbcTemplate jdbc;

    public GlobalModelSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按 user_id 映射默认供应商、两个供应商模型及 Codex 强度，缺失记录返回 Optional.empty。
     *
     * @param userId 模型配置所属用户 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<GlobalModelSettings> find(UUID userId) {
        return jdbc.query("SELECT * FROM user_model_settings WHERE user_id = ?", (rs, row) ->
                new GlobalModelSettings(ModelProvider.valueOf(rs.getString("provider")),
                        rs.getString("codex_model"), rs.getString("codex_effort"),
                        rs.getString("deepseek_model"), rs.getLong("row_version")), userId).stream().findFirst();
    }

    /**
     * 版本为 0 时冲突忽略插入，否则按 row_version 更新并递增；返回实际影响是否为一行，不吞掉并发覆盖。
     *
     * @param userId 模型配置所属用户 ID。
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    public boolean save(UUID userId, GlobalModelSettings value) {
        if (value.version() == 0) {
            return jdbc.update("""
                    INSERT INTO user_model_settings(user_id, provider, codex_model, codex_effort, deepseek_model)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (user_id) DO NOTHING
                    """, userId, value.provider().name(), value.codexModel(), value.codexEffort(),
                    value.deepSeekModel()) == 1;
        }
        return jdbc.update("""
                UPDATE user_model_settings SET provider = ?, codex_model = ?, codex_effort = ?,
                    deepseek_model = ?, row_version = row_version + 1, updated_at = now()
                WHERE user_id = ? AND row_version = ?
                """, value.provider().name(), value.codexModel(), value.codexEffort(),
                value.deepSeekModel(), userId, value.version()) == 1;
    }
}
