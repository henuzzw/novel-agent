package com.novelagent.canon.infrastructure;

import static com.novelagent.canon.infrastructure.CanonFactProjectionText.hasText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.normalizedText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.truncate;

import com.novelagent.canon.application.CanonFactProjector;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ForeshadowFactProjector implements CanonFactProjector {
    private static final String INSERT_SQL = """
        INSERT INTO foreshadow
            (id, project_id, source_fact_id, title, target_effect, current_status,
             planned_resolve_chapter, canon_version_from, source_commit_id, evidence_ref)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;

    public ForeshadowFactProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> factTypes() {
        return Set.of("FORESHADOW", "FORESHADOW_CHANGE");
    }

    @Override
    public boolean requiresSubject(FactProposal proposal) {
        return false;
    }

    @Override
    public void project(Projection projection) {
        FactProposal proposal = projection.proposal();
        TypedFactPayload payload = proposal.payload();
        String title = hasText(payload == null ? null : payload.foreshadowTitle())
                ? payload.foreshadowTitle() : normalizedText(proposal.subject()) + normalizedText(proposal.predicate());
        String targetEffect = hasText(payload == null ? null : payload.targetEffect())
                ? payload.targetEffect() : proposal.object();
        String status = hasText(payload == null ? null : payload.foreshadowStatus())
                ? payload.foreshadowStatus() : "PLANTED";
        Integer plannedChapter = payload == null ? null : payload.plannedResolveChapter();
        jdbc.update(INSERT_SQL, UUID.randomUUID(), projection.projectId(), projection.factId(),
                truncate(title, 300), targetEffect, status, plannedChapter, projection.canonVersion(), projection.commitId(),
                proposal.evidence());
    }

    @Override
    public void retire(UUID commitId, long replacementVersion) {
        jdbc.update("UPDATE foreshadow SET canon_version_to = ? "
                + "WHERE source_commit_id = ? AND canon_version_to IS NULL", replacementVersion, commitId);
    }
}
