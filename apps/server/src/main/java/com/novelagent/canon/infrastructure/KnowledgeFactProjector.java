package com.novelagent.canon.infrastructure;

import static com.novelagent.platform.support.Text.hasText;

import com.novelagent.canon.application.CanonFactProjector;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeFactProjector implements CanonFactProjector {
    private static final String INSERT_SQL = """
        INSERT INTO character_knowledge
            (id, project_id, character_id, fact_id, knowledge_type, belief_truth,
             confidence, narrative_chapter, canon_version_from, source_commit_id, evidence_ref)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;

    public KnowledgeFactProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> factTypes() {
        return Set.of("KNOWLEDGE", "KNOWLEDGE_CHANGE");
    }

    @Override
    public void project(Projection projection) {
        FactProposal proposal = projection.proposal();
        TypedFactPayload payload = proposal.payload();
        String knowledgeType = hasText(payload == null ? null : payload.knowledgeType())
                ? payload.knowledgeType() : "LEARNED";
        String beliefTruth = hasText(payload == null ? null : payload.beliefTruth())
                ? payload.beliefTruth() : "UNVERIFIED";
        double confidence = proposal.confidence() == null ? 0.7 : proposal.confidence();
        jdbc.update(INSERT_SQL, UUID.randomUUID(), projection.projectId(), projection.subjectId(), projection.factId(), knowledgeType, beliefTruth,
                confidence, projection.chapterNumber(), projection.canonVersion(), projection.commitId(), proposal.evidence());
    }

    @Override
    public void retire(UUID commitId, long replacementVersion) {
        jdbc.update("UPDATE character_knowledge SET canon_version_to = ? "
                + "WHERE source_commit_id = ? AND canon_version_to IS NULL", replacementVersion, commitId);
    }
}
