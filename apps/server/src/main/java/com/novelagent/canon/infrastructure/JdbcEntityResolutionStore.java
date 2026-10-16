package com.novelagent.canon.infrastructure;

import com.novelagent.canon.application.EntityResolutionStore;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEntityResolutionStore implements EntityResolutionStore {
    private static final String REQUESTED_SQL = """
            SELECT id FROM story_entity
            WHERE id = ? AND project_id = ? AND entity_type = ? AND canon_version_to IS NULL
            """;
    private static final String CANONICAL_SQL = """
            SELECT id FROM story_entity
            WHERE project_id = ? AND entity_type = ? AND lower(canonical_name) = lower(?)
              AND canon_version_to IS NULL
            """;
    private static final String ALIAS_SQL = """
            SELECT DISTINCT a.entity_id
            FROM entity_alias a
            JOIN story_entity e ON e.id = a.entity_id
            WHERE a.project_id = ? AND e.entity_type = ? AND lower(a.alias) = lower(?)
              AND a.canon_version_to IS NULL AND e.canon_version_to IS NULL
            """;
    private static final String CREATE_SQL = """
            INSERT INTO story_entity
                (id, project_id, entity_type, canonical_name, canon_version_from,
                 source_commit_id, evidence_ref)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id, entity_type, canonical_name)
            DO UPDATE SET canonical_name = EXCLUDED.canonical_name
            RETURNING id
            """;
    private static final String MENTION_SQL = """
            INSERT INTO entity_mention
                (id, project_id, source_commit_id, proposal_id, mention_role, mention_text,
                 requested_entity_id, resolved_entity_id, resolution_method, confidence, evidence_ref)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT uq_entity_mention_source
            DO UPDATE SET mention_text = EXCLUDED.mention_text,
                requested_entity_id = EXCLUDED.requested_entity_id,
                resolved_entity_id = EXCLUDED.resolved_entity_id,
                resolution_method = EXCLUDED.resolution_method,
                confidence = EXCLUDED.confidence, evidence_ref = EXCLUDED.evidence_ref
            """;
    private final JdbcTemplate jdbc;

    public JdbcEntityResolutionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean containsActive(UUID projectId, UUID entityId, String type) {
        return !jdbc.query(REQUESTED_SQL, (rs, row) -> rs.getObject("id", UUID.class), entityId, projectId, type).isEmpty();
    }

    @Override
    public List<UUID> canonicalMatches(UUID projectId, String type, String name) {
        return jdbc.query(CANONICAL_SQL, (rs, row) -> rs.getObject("id", UUID.class), projectId, type, name);
    }

    @Override
    public List<UUID> aliasMatches(UUID projectId, String type, String name) {
        return jdbc.query(ALIAS_SQL, (rs, row) -> rs.getObject("entity_id", UUID.class), projectId, type, name);
    }

    @Override
    public UUID create(UUID projectId, UUID commitId, long canonVersion, String type, String name, String evidence) {
        return jdbc.queryForObject(CREATE_SQL, UUID.class, UUID.randomUUID(), projectId, type, name, canonVersion, commitId, evidence);
    }

    @Override
    public void recordMention(Mention mention) {
        jdbc.update(MENTION_SQL, UUID.randomUUID(), mention.projectId(), mention.commitId(), mention.proposalId(),
                mention.role(), mention.text(), mention.requestedId(), mention.resolvedId(), mention.method(),
                mention.confidence(), mention.evidence());
    }
}
