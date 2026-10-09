package com.novelagent.modelaccess.application;

import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ResourceVersionConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit, user-scoped transport choice. Never silently falls back to another account/provider. */
@Service
public class ChatGptTransportService {
    public enum Transport { APP_SERVER, SIWC_HTTP }
    public record Choice(Transport transport, long version) { }
    private final JdbcTemplate jdbc;
    private final CurrentActorProvider actors;
    private final ChatGptOAuthService auth;
    private final java.sql.Timestamp instanceStarted = new java.sql.Timestamp(java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
    public ChatGptTransportService(JdbcTemplate jdbc, CurrentActorProvider actors, ChatGptOAuthService auth) {
        this.jdbc = jdbc; this.actors = actors; this.auth = auth;
    }
    public Choice get() {
        return jdbc.query("SELECT transport, row_version FROM user_chatgpt_transport WHERE user_id = ?",
                (rs, row) -> new Choice(Transport.valueOf(rs.getString(1)), rs.getLong(2)), actors.currentUserId())
                .stream().findFirst().orElse(new Choice(Transport.APP_SERVER, 0));
    }
    public boolean direct() { return get().transport() == Transport.SIWC_HTTP; }
    public void requireIdle() {
        Boolean running = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM agent_run a JOIN novel_project p ON p.id = a.project_id
                    WHERE p.owner_id = ? AND a.status = 'RUNNING' AND a.started_at >= ?)
                    OR EXISTS (SELECT 1 FROM model_http_conversation c JOIN novel_project p ON p.id=c.project_id
                        WHERE p.owner_id=? AND c.lease_id IS NOT NULL AND c.lease_until > now())
                """, Boolean.class, actors.currentUserId(), instanceStarted, actors.currentUserId());
        if (Boolean.TRUE.equals(running)) throw new IllegalStateException("有进行中的模型任务，请先结束任务再切换入口或断开账号");
    }
    @Transactional
    public Choice update(Choice choice) {
        if (choice == null || choice.transport() == null || choice.version() < 0) throw new IllegalArgumentException("请选择 ChatGPT 接入方式");
        requireIdle();
        if (choice.transport() == Transport.SIWC_HTTP) auth.authorization();
        int changed = choice.version() == 0 ? jdbc.update("""
                INSERT INTO user_chatgpt_transport(user_id, transport) VALUES (?, ?) ON CONFLICT DO NOTHING
                """, actors.currentUserId(), choice.transport().name()) : jdbc.update("""
                UPDATE user_chatgpt_transport SET transport = ?, row_version = row_version + 1, updated_at = now()
                    WHERE user_id = ? AND row_version = ?
                """, choice.transport().name(), actors.currentUserId(), choice.version());
        if (changed != 1) throw new ResourceVersionConflictException(choice.version(), get().version());
        return new Choice(choice.transport(), choice.version() + 1);
    }
}
