package com.novelagent.project.infrastructure;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GlobalModelSettingsRepository {
    private final JdbcTemplate jdbc;

    public GlobalModelSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GlobalModelSettings> find(UUID userId) {
        return jdbc.query("SELECT * FROM user_model_settings WHERE user_id = ?", (rs, row) ->
                new GlobalModelSettings(ModelProvider.valueOf(rs.getString("provider")),
                        rs.getString("codex_model"), rs.getString("codex_effort"),
                        rs.getString("deepseek_model"), rs.getLong("row_version")), userId).stream().findFirst();
    }

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
