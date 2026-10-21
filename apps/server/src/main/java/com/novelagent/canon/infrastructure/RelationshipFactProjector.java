package com.novelagent.canon.infrastructure;

import static com.novelagent.platform.support.Text.hasText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.normalizedText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.truncate;

import com.novelagent.canon.application.CanonFactProjector;
import com.novelagent.canon.application.EntityResolutionService;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class RelationshipFactProjector implements CanonFactProjector {
    private static final String INSERT_SQL = """
        INSERT INTO story_relationship
            (id, project_id, source_entity_id, target_entity_id, source_fact_id,
             relation_type, canon_version_from, source_commit_id, evidence_ref)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;
    private final EntityResolutionService entityResolution;

    public RelationshipFactProjector(
            JdbcTemplate jdbc,
            EntityResolutionService entityResolution) {
        this.jdbc = jdbc;
        this.entityResolution = entityResolution;
    }

    @Override
    public Set<String> factTypes() {
        return Set.of("RELATION", "RELATION_CHANGE");
    }

    @Override
    public void project(Projection projection) {
        FactProposal proposal = projection.proposal();
        TypedFactPayload payload = proposal.payload();
        String targetName = hasText(payload == null ? null : payload.targetEntityName())
                ? payload.targetEntityName() : proposal.object();
        String relationType = hasText(payload == null ? null : payload.relationType())
                ? payload.relationType() : proposal.predicate();
        UUID targetId = entityResolution.resolve(projection.projectId(), projection.commitId(), projection.canonVersion(),
                proposal.id(), "TARGET", "CHARACTER", targetName,
                payload == null ? null : payload.targetEntityId(), proposal.evidence());
        if (projection.subjectId().equals(targetId)) {
            return;
        }
        jdbc.update(INSERT_SQL, UUID.randomUUID(), projection.projectId(), projection.subjectId(), targetId, projection.factId(),
                truncate(normalizedText(relationType), 100), projection.canonVersion(), projection.commitId(),
                proposal.evidence());
    }

    @Override
    public void retire(UUID commitId, long replacementVersion) {
        jdbc.update("UPDATE story_relationship SET canon_version_to = ? "
                + "WHERE source_commit_id = ? AND canon_version_to IS NULL", replacementVersion, commitId);
    }
}
