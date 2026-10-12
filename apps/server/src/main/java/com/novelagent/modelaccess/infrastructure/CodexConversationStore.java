package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.project.application.CurrentActorProvider;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Cross-process project ordering with short transactions, including isolated editorial turns. */
@Repository
public class CodexConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentActorProvider actors;
    private final TransactionTemplate transactions;
    public CodexConversationStore(JdbcTemplate jdbc, ObjectMapper json, CurrentActorProvider actors, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.json = json; this.actors = actors;
        transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Lease claim(UUID project, String workflow, String binding, boolean fresh) {
        return transactions.execute(status -> {
            var owned = jdbc.queryForList("SELECT id FROM novel_project WHERE id=? AND owner_id=? FOR UPDATE", project, actors.currentUserId());
            if (owned.isEmpty()) throw new IllegalArgumentException("小说项目不存在或不属于当前用户");
            Boolean busy = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM model_codex_conversation WHERE project_id=?
                        AND lease_id IS NOT NULL AND lease_until > now())
                    """, Boolean.class, project);
            if (Boolean.TRUE.equals(busy)) throw new IllegalStateException("该项目会话正在生成，请先等待或停止当前任务");
            jdbc.update("INSERT INTO model_codex_conversation(project_id,workflow,account_binding) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    project, workflow, binding);
            var row = jdbc.queryForMap("SELECT * FROM model_codex_conversation WHERE project_id=? AND workflow=? FOR UPDATE", project, workflow);
            // An expired lease means an uncertain remote turn: do not blindly reuse its thread.
            boolean reset = fresh || !binding.equals(row.get("account_binding")) || row.get("lease_id") != null;
            String thread = reset ? null : (String) row.get("thread_id");
            var sent = new LinkedHashSet<String>();
            try { if (!reset) json.readTree(row.get("sent_materials").toString()).forEach(item -> sent.add(item.asText())); }
            catch (Exception error) { throw new IllegalStateException("会话资料目录不可读，未覆盖原记录"); }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    UPDATE model_codex_conversation SET account_binding=?, thread_id=?, sent_materials=CAST(? AS jsonb),
                        lease_id=?, lease_until=now()+interval '30 minutes', updated_at=now()
                        WHERE project_id=? AND workflow=?
                    """, binding, thread, encode(sent), id, project, workflow);
            return new Lease(project, workflow, id, thread, Set.copyOf(sent), reset ? 0 : ((Number) row.get("context_tokens")).longValue());
        });
    }
    public void bind(Lease lease, String thread) {
        require(jdbc.update("UPDATE model_codex_conversation SET thread_id=? WHERE project_id=? AND workflow=? AND lease_id=?",
                thread, lease.project(), lease.workflow(), lease.id()));
    }
    public void saveMaterials(Lease lease, Map<String, String> materials) {
        transactions.executeWithoutResult(status -> {
            var active = jdbc.queryForList("SELECT project_id FROM model_codex_conversation WHERE project_id=? AND workflow=? AND lease_id=? FOR UPDATE",
                    lease.project(), lease.workflow(), lease.id());
            if (active.isEmpty()) throw new IllegalStateException("会话租约已失效");
            materials.forEach((id, content) -> jdbc.update("INSERT INTO model_codex_material(project_id,material_id,content) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                    lease.project(), id, content));
        });
    }
    public String readMaterial(Lease lease, String id) {
        return jdbc.query("""
                SELECT m.content FROM model_codex_material m JOIN model_codex_conversation c ON c.project_id=m.project_id
                    WHERE m.project_id=? AND m.material_id=? AND c.workflow=? AND c.lease_id=? AND c.lease_until > now()
                """, (rs, n) -> rs.getString(1), lease.project(), id, lease.workflow(), lease.id()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("当前任务资料不存在或租约已失效"));
    }
    public void renew(Lease lease) {
        require(jdbc.update("UPDATE model_codex_conversation SET lease_until=now()+interval '30 minutes' WHERE project_id=? AND workflow=? AND lease_id=?",
                lease.project(), lease.workflow(), lease.id()));
    }
    public void complete(Lease lease, Set<String> sent, long contextTokens) {
        require(jdbc.update("""
                UPDATE model_codex_conversation SET sent_materials=CAST(? AS jsonb), context_tokens=?, lease_id=NULL,
                    lease_until=NULL, updated_at=now() WHERE project_id=? AND workflow=? AND lease_id=?
                """, encode(sent), contextTokens, lease.project(), lease.workflow(), lease.id()));
    }
    public void abort(Lease lease) {
        // A failed/invalid response must not become hidden accepted context on the next turn.
        jdbc.update("""
                UPDATE model_codex_conversation SET thread_id=NULL, sent_materials='[]', context_tokens=0,
                    lease_id=NULL, lease_until=NULL WHERE project_id=? AND workflow=? AND lease_id=?
                """, lease.project(), lease.workflow(), lease.id());
    }
    public void release(Lease lease) {
        jdbc.update("UPDATE model_codex_conversation SET lease_id=NULL, lease_until=NULL WHERE project_id=? AND workflow=? AND lease_id=?",
                lease.project(), lease.workflow(), lease.id());
    }
    private String encode(Set<String> values) {
        try { return json.writeValueAsString(values); } catch (Exception error) { throw new IllegalStateException(error); }
    }
    private void require(int changed) { if (changed != 1) throw new IllegalStateException("会话租约已失效，未保存结果"); }
    public record Lease(UUID project, String workflow, UUID id, String thread, Set<String> sent, long contextTokens) { }
}
