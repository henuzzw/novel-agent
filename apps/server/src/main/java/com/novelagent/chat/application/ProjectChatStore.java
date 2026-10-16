package com.novelagent.chat.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.chat.domain.ChatSource;
import com.novelagent.project.application.ProjectAccessService;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Short transactions own turn lifecycle, proposals and optimistic author confirmations. Models never hold locks. */
@Service
public class ProjectChatStore {
    public record Reference(String key, String title, int offset, int end, int characters, String fingerprint) { }
    public record Proposal(ChatSource source, String replacement, String reason, String impact) { }
    public record Change(UUID id, UUID turnId, String key, String title, String before, String after,
            String reason, String impact, String status, String resultKey, Instant createdAt) { }
    public record Turn(UUID id, String question, String reply, String status, String error,
            JsonNode sources, Instant createdAt) { }
    public record Creation(Turn turn, boolean created) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessService access;
    private final ChatMaterials materials;
    private final String identity;

    public ProjectChatStore(JdbcTemplate jdbc, ObjectMapper json, ProjectAccessService access, ChatMaterials materials,
            @Value("${app.instance-id:}") String configured, @Value("${server.port:8080}") String port,
            @Value("${app.role:all}") String role) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.materials = materials;
        identity = configured.isBlank() ? System.getenv().getOrDefault("COMPUTERNAME",
                System.getenv().getOrDefault("HOSTNAME", "local")) + ":" + port + ":" + role : configured;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        jdbc.update("""
                UPDATE project_chat_turn SET status='FAILED',error_message='服务重启，对话未完成；请手动重新发送',updated_at=now()
                WHERE status='RUNNING' AND worker_identity=?
                """, identity);
    }

    @Transactional
    public Creation create(UUID project, UUID id, String question) {
        access.requireOwnedProject(project);
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id=? FOR UPDATE", UUID.class, project);
        var found = turns(project, "AND id=?", id);
        if (!found.isEmpty()) {
            if (!found.getFirst().question().equals(question)) throw new IllegalArgumentException("同一请求编号不能重复用于不同问题");
            return new Creation(found.getFirst(), false);
        }
        if (jdbc.queryForObject("SELECT count(*) FROM project_chat_turn WHERE project_id=? AND status='RUNNING'", Integer.class, project) > 0)
            throw new IllegalArgumentException("项目创作对话正在请求，请等待或停止");
        jdbc.update("INSERT INTO project_chat_turn(id,project_id,question,status,worker_identity) VALUES (?,?,?,'RUNNING',?)",
                id, project, question, identity);
        return new Creation(get(project, id), true);
    }

    public List<Turn> list(UUID project) {
        access.requireOwnedProject(project);
        return turns(project, "ORDER BY created_at DESC,id DESC LIMIT 100").reversed();
    }
    public Turn get(UUID project, UUID id) {
        access.requireOwnedProject(project);
        return turns(project, "AND id=?", id).stream().findFirst().orElseThrow(() -> new IllegalArgumentException("对话不存在"));
    }
    public boolean active(UUID project, UUID id) { return get(project, id).status().equals("RUNNING"); }

    public void progress(UUID project, UUID id, List<Reference> sources) {
        jdbc.update("UPDATE project_chat_turn SET sources=CAST(? AS jsonb),updated_at=now() WHERE project_id=? AND id=? AND status='RUNNING'",
                json.valueToTree(sources).toString(), project, id);
    }

    @Transactional
    public void complete(UUID project, UUID id, String reply, List<Reference> sources, List<Proposal> proposals) {
        access.requireOwnedProject(project);
        jdbc.queryForObject("SELECT id FROM project_chat_turn WHERE id=? AND project_id=? FOR UPDATE", UUID.class, id, project);
        if (!active(project, id)) throw new IllegalArgumentException("对话已停止，迟到结果不保存");
        for (var proposal : proposals) {
            ChatMaterials.checkCurrent(proposal.source(), materials.require(project, proposal.source().key()));
            jdbc.update("""
                    INSERT INTO project_chat_change(id,project_id,turn_id,source,replacement,reason,impact)
                    VALUES (?,?,?,CAST(? AS jsonb),?,?,?)
                    """, UUID.randomUUID(), project, id, json.valueToTree(proposal.source()).toString(),
                    proposal.replacement(), proposal.reason(), proposal.impact());
        }
        jdbc.update("UPDATE project_chat_turn SET status='SUCCEEDED',reply=?,sources=CAST(? AS jsonb),updated_at=now() WHERE id=? AND project_id=?",
                reply, json.valueToTree(sources).toString(), id, project);
    }

    public void fail(UUID project, UUID id, String status, String error) {
        access.requireOwnedProject(project);
        String safe = error == null ? "对话请求失败，请查看模型任务详情" : error.substring(0, Math.min(error.length(), 1000));
        jdbc.update("UPDATE project_chat_turn SET status=?,error_message=?,updated_at=now() WHERE project_id=? AND id=? AND status='RUNNING'",
                status, safe, project, id);
    }

    public List<Change> changes(UUID project) {
        access.requireOwnedProject(project);
        return jdbc.query("SELECT * FROM project_chat_change WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 100", (rs, n) -> {
            var source = decode(rs.getString("source"), ChatSource.class);
            JsonNode result = parse(rs.getString("result_source"));
            return new Change(rs.getObject("id", UUID.class), rs.getObject("turn_id", UUID.class), source.key(), source.title(),
                    source.text(), rs.getString("replacement"), rs.getString("reason"), rs.getString("impact"), rs.getString("status"),
                    result == null ? null : result.path("key").asText(), rs.getTimestamp("created_at").toInstant());
        }, project);
    }

    /** Explicit author command only; a stale source rolls back both revision and change-state transition. */
    @Transactional
    public void decide(UUID project, UUID id, String action) {
        access.requireOwnedProject(project);
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id=? FOR UPDATE", UUID.class, project);
        var rows = jdbc.queryForList("SELECT * FROM project_chat_change WHERE project_id=? AND id=? FOR UPDATE", project, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("修改候选不存在");
        var row = rows.getFirst();
        String status = row.get("status").toString();
        var before = decode(row.get("source").toString(), ChatSource.class);
        if (action.equals("reject")) {
            if (!status.equals("PENDING")) throw new IllegalArgumentException("只有待确认候选可以放弃");
            jdbc.update("""
                    UPDATE project_chat_change SET status='REJECTED',updated_at=now() WHERE project_id=? AND turn_id=?
                      AND status='PENDING' AND source->>'recordId'=? AND source->>'kind'=?
                    """, project, row.get("turn_id"), before.recordId(), before.kind());
            return;
        }
        if (!(action.equals("apply") && status.equals("PENDING")) && !(action.equals("undo") && status.equals("APPLIED")))
            throw new IllegalArgumentException("修改状态不允许此操作");
        var group = jdbc.queryForList("""
                SELECT * FROM project_chat_change WHERE project_id=? AND turn_id=? AND status=?
                  AND source->>'recordId'=? AND source->>'kind'=? ORDER BY created_at,id FOR UPDATE
                """, project, row.get("turn_id"), status, before.recordId(), before.kind());
        boolean undo = action.equals("undo");
        var siblings = new ArrayList<Proposal>();
        for (var member : group) {
            if (member.get("id").equals(id)) continue;
            var original = decode(member.get("source").toString(), ChatSource.class);
            var target = undo ? decode(member.get("result_source").toString(), ChatSource.class) : original;
            siblings.add(new Proposal(target, undo ? original.text() : member.get("replacement").toString(), "", ""));
        }
        var expected = undo ? decode(row.get("result_source").toString(), ChatSource.class) : before;
        var result = materials.apply(project, expected, undo ? before.text() : row.get("replacement").toString(),
                undo ? before : null, siblings);
        for (var member : group) {
            var original = decode(member.get("source").toString(), ChatSource.class);
            var saved = materials.require(project, original.kind() + ":" + result.recordId() + ":" + original.field());
            jdbc.update("UPDATE project_chat_change SET status=?,result_source=CAST(? AS jsonb),updated_at=now() WHERE id=? AND project_id=?",
                    undo ? "UNDONE" : "APPLIED", json.valueToTree(saved).toString(), member.get("id"), project);
        }
    }

    private List<Turn> turns(UUID project, String suffix, Object... args) {
        var parameters = new ArrayList<Object>(); parameters.add(project); parameters.addAll(Arrays.asList(args));
        return jdbc.query("SELECT * FROM project_chat_turn WHERE project_id=? " + suffix, (rs, n) -> new Turn(
                rs.getObject("id", UUID.class), rs.getString("question"), rs.getString("reply"), rs.getString("status"),
                rs.getString("error_message"), parse(rs.getString("sources")), rs.getTimestamp("created_at").toInstant()), parameters.toArray());
    }
    private JsonNode parse(String text) {
        try { return text == null ? null : json.readTree(text); } catch (Exception e) { throw new IllegalStateException("对话记录无法读取", e); }
    }
    private <T> T decode(String text, Class<T> type) {
        try { return json.readValue(text, type); } catch (Exception e) { throw new IllegalStateException("修改快照无法读取", e); }
    }
}
