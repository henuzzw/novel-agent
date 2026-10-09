package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.novelagent.project.application.CurrentActorProvider;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Short database leases protect conversation order; model waiting never holds a transaction open. */
@Repository
public class HttpConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final TransactionTemplate transactions;
    public HttpConversationStore(JdbcTemplate jdbc, ObjectMapper json, CurrentActorProvider actors, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.actors = actors;
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Lease claim(UUID project, String workflow, String binding, String revision, boolean fresh) {
        return transactions.execute(status -> {
            Boolean owned = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM novel_project WHERE id=? AND owner_id=?)",
                    Boolean.class, project, actors.currentUserId());
            if (!Boolean.TRUE.equals(owned)) throw new IllegalArgumentException("小说项目不存在或不属于当前用户");
            jdbc.update("""
                    INSERT INTO model_http_conversation(project_id, workflow, conversation_id, account_binding, prompt_revision)
                        VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, project, workflow, UUID.randomUUID(), binding, revision);
            var stored = jdbc.queryForMap("SELECT * FROM model_http_conversation WHERE project_id=? AND workflow=? FOR UPDATE", project, workflow);
            if (stored.get("lease_id") != null) {
                Boolean expired = jdbc.queryForObject("SELECT lease_until < now() FROM model_http_conversation WHERE project_id=? AND workflow=?",
                        Boolean.class, project, workflow);
                if (!Boolean.TRUE.equals(expired)) throw new IllegalStateException("该项目会话正在生成，请先等待或停止当前任务");
            }
            boolean replace = fresh || !binding.equals(stored.get("account_binding")) || !java.util.Objects.equals(revision, stored.get("prompt_revision"));
            UUID conversation = replace ? UUID.randomUUID() : (UUID) stored.get("conversation_id");
            ArrayNode history;
            try { history = replace ? json.createArrayNode() : (ArrayNode) json.readTree(stored.get("input_history").toString()); }
            catch (Exception error) { throw new IllegalStateException("会话历史不可读，未覆盖原记录"); }
            UUID lease = UUID.randomUUID();
            jdbc.update("""
                    UPDATE model_http_conversation SET lease_id=?, lease_until=now()+interval '30 minutes',
                        conversation_id=?, account_binding=?, prompt_revision=?, input_history=CAST(? AS jsonb), updated_at=now()
                        WHERE project_id=? AND workflow=?
                    """, lease, conversation, binding, revision, history.toString(), project, workflow);
            return new Lease(project, workflow, lease, conversation, history);
        });
    }
    public void renew(Lease lease) {
        int changed = jdbc.update("""
                UPDATE model_http_conversation SET lease_until=now()+interval '30 minutes'
                    WHERE project_id=? AND workflow=? AND lease_id=?
                """, lease.project(), lease.workflow(), lease.id());
        if (changed != 1) throw new IllegalStateException("会话租约已失效，未保存结果");
    }
    public void complete(Lease lease, JsonNode history) {
        int changed = jdbc.update("""
                UPDATE model_http_conversation SET input_history=CAST(? AS jsonb), lease_id=NULL, lease_until=NULL, updated_at=now()
                    WHERE project_id=? AND workflow=? AND lease_id=?
                """, history.toString(), lease.project(), lease.workflow(), lease.id());
        if (changed != 1) throw new IllegalStateException("会话已被替换，未保存旧响应");
    }
    public void release(Lease lease) {
        jdbc.update("UPDATE model_http_conversation SET lease_id=NULL, lease_until=NULL WHERE project_id=? AND workflow=? AND lease_id=?",
                lease.project(), lease.workflow(), lease.id());
    }
    public record Lease(UUID project, String workflow, UUID id, UUID conversation, ArrayNode history) { }
}
