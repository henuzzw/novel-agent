package com.novelagent.canon.application;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 实体消歧。
 *
 * <p>将候选事实中的 ID 或名称解析为当前项目实体，必要时在接受事实物化过程中创建实体。存在同名歧义时拒绝自动选取，避免跨人物合并；名称出现记录与实体身份分开。</p>
 */
@Component
public class EntityResolutionService {
    private static final Set<String> PRONOUNS = Set.of(
            "他", "她", "它", "他们", "她们", "它们", "自己", "对方", "那个人", "这个人");

    private final JdbcTemplate jdbc;

    public EntityResolutionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 优先核对明确实体 ID，再通过本项目已有名称或别名消歧；未能唯一确定时按允许类型创建或拒绝，记录事实来源中的出现证据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param commitId 关联的正史提交 ID。
     * @param canonVersion 有效正史版本水位，与记录行版本不同。
     * @param proposalId 审稿候选事实 ID，用于追溯出现来源。
     * @param role 候选事实中该实体承担的角色。
     * @param entityType 候选实体类型，必须与现有身份一致。
     * @param mention 正文或候选事实中的实体名称原文。
     * @param requestedEntityId 模型明确引用的实体 ID；必须存在于本项目，不能虚构。
     * @param evidence 来源中的连续原文证据，不允许拼接或伪造引文。
     */
    public UUID resolve(UUID projectId, UUID commitId, long canonVersion, String proposalId,
            String role, String entityType, String mention, String requestedEntityId, String evidence) {
        String name = required(mention, "实体名称");
        String type = required(entityType, "实体类型").toUpperCase(Locale.ROOT);
        UUID requestedId = parseId(requestedEntityId);
        Resolution resolution;
        if (requestedId != null) {
            resolution = resolveRequested(projectId, requestedId, type);
        } else {
            resolution = resolveByName(projectId, name, type, commitId, canonVersion, evidence);
        }
        recordMention(projectId, commitId, proposalId, role, name, requestedId, resolution, evidence);
        return resolution.entityId();
    }

    private Resolution resolveRequested(UUID projectId, UUID entityId, String type) {
        List<UUID> matches = jdbc.query("""
                SELECT id FROM story_entity
                WHERE id = ? AND project_id = ? AND entity_type = ? AND canon_version_to IS NULL
                """, (rs, row) -> rs.getObject("id", UUID.class), entityId, projectId, type);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("候选事实引用的实体 ID 不存在、类型不符或不属于当前项目");
        }
        return new Resolution(entityId, "REQUESTED_ID", 1.0);
    }

    private Resolution resolveByName(UUID projectId, String name, String type, UUID commitId,
            long canonVersion, String evidence) {
        List<UUID> canonical = jdbc.query("""
                SELECT id FROM story_entity
                WHERE project_id = ? AND entity_type = ? AND lower(canonical_name) = lower(?)
                  AND canon_version_to IS NULL
                """, (rs, row) -> rs.getObject("id", UUID.class), projectId, type, name);
        if (canonical.size() == 1) return new Resolution(canonical.getFirst(), "CANONICAL_NAME", 1.0);
        if (canonical.size() > 1) throw ambiguous(name);

        List<UUID> aliases = jdbc.query("""
                SELECT DISTINCT a.entity_id
                FROM entity_alias a
                JOIN story_entity e ON e.id = a.entity_id
                WHERE a.project_id = ? AND e.entity_type = ? AND lower(a.alias) = lower(?)
                  AND a.canon_version_to IS NULL AND e.canon_version_to IS NULL
                """, (rs, row) -> rs.getObject("entity_id", UUID.class), projectId, type, name);
        if (aliases.size() == 1) return new Resolution(aliases.getFirst(), "CONFIRMED_ALIAS", 0.98);
        if (aliases.size() > 1) throw ambiguous(name);
        if (PRONOUNS.contains(name)) {
            throw new IllegalArgumentException("无法确定代词“" + name + "”指向哪个实体，请在审稿中选择具体人物");
        }
        return new Resolution(createEntity(projectId, commitId, canonVersion, type, name, evidence),
                "CREATED", 0.85);
    }

    private UUID createEntity(UUID projectId, UUID commitId, long canonVersion, String type,
            String name, String evidence) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO story_entity
                    (id, project_id, entity_type, canonical_name, canon_version_from,
                     source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (project_id, entity_type, canonical_name)
                DO UPDATE SET canonical_name = EXCLUDED.canonical_name
                RETURNING id
                """, UUID.class, id, projectId, type, name, canonVersion, commitId, evidence);
    }

    private void recordMention(UUID projectId, UUID commitId, String proposalId, String role,
            String mention, UUID requestedId, Resolution resolution, String evidence) {
        jdbc.update("""
                INSERT INTO entity_mention
                    (id, project_id, source_commit_id, proposal_id, mention_role, mention_text,
                     requested_entity_id, resolved_entity_id, resolution_method, confidence, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, commitId, proposalId, role, mention, requestedId,
                resolution.entityId(), resolution.method(), resolution.confidence(), evidence);
    }

    private UUID parseId(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("候选事实中的实体 ID 格式不正确", exception);
        }
    }

    private IllegalArgumentException ambiguous(String mention) {
        return new IllegalArgumentException("实体名称或别名“" + mention + "”对应多个对象，请先完成实体消歧");
    }

    private String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private record Resolution(UUID entityId, String method, double confidence) {
    }
}
