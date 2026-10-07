package com.novelagent.prompt.infrastructure;

import com.novelagent.prompt.domain.PromptConfiguration;
import com.novelagent.prompt.domain.PromptRevision;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 用户级提示词存储。当前配置使用版本条件更新，历史记录在同一短事务内追加。 */
@Repository
public class AgentPromptRepository {
    private static final RowMapper<PromptConfiguration> MAPPER = (rs, row) -> new PromptConfiguration(
            rs.getString("template_key"), rs.getString("system_prompt"), rs.getString("guidance"),
            rs.getLong("row_version"), rs.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;

    public AgentPromptRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<PromptConfiguration> findAll(UUID userId) {
        return jdbc.query("SELECT * FROM user_agent_prompt WHERE user_id = ?", MAPPER, userId);
    }

    public Optional<PromptConfiguration> find(UUID userId, String key) {
        return jdbc.query("SELECT * FROM user_agent_prompt WHERE user_id = ? AND template_key = ?",
                MAPPER, userId, key).stream().findFirst();
    }

    /** 首次插入防重复，后续更新要求作者看到的版本仍有效；失败时不追加虚假的历史。 */
    public boolean save(UUID userId, String key, String system, String guidance, long expected, String operation) {
        int changed = expected == 0
                ? jdbc.update("""
                    INSERT INTO user_agent_prompt(user_id, template_key, system_prompt, guidance)
                    VALUES (?, ?, ?, ?) ON CONFLICT (user_id, template_key) DO NOTHING
                    """, userId, key, system, guidance)
                : jdbc.update("""
                    UPDATE user_agent_prompt SET system_prompt = ?, guidance = ?, row_version = row_version + 1,
                        updated_at = now() WHERE user_id = ? AND template_key = ? AND row_version = ?
                    """, system, guidance, userId, key, expected);
        if (changed != 1) return false;
        jdbc.update("""
                INSERT INTO user_agent_prompt_revision(user_id, template_key, revision, system_prompt, guidance, operation)
                VALUES (?, ?, ?, ?, ?, ?)
                """, userId, key, expected + 1, system, guidance, operation);
        return true;
    }

    public List<PromptRevision> history(UUID userId, String key) {
        return jdbc.query("""
                SELECT * FROM user_agent_prompt_revision WHERE user_id = ? AND template_key = ?
                ORDER BY revision DESC LIMIT 50
                """, (rs, row) -> new PromptRevision(rs.getLong("revision"), rs.getString("system_prompt"),
                        rs.getString("guidance"), rs.getString("operation"), rs.getTimestamp("created_at").toInstant()),
                userId, key);
    }
}
