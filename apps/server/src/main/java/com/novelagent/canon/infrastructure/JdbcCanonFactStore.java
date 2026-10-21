package com.novelagent.canon.infrastructure;

import static com.novelagent.canon.infrastructure.CanonFactProjectionText.normalizedText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.retireCommit;

import com.novelagent.canon.application.CanonFactStore;
import com.novelagent.writing.domain.FactProposal;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCanonFactStore implements CanonFactStore {
    private static final String INSERT_SQL = """
            INSERT INTO story_fact
                (id, project_id, proposal_id, fact_type, subject_entity_id, subject_text,
                 predicate, object_text, canon_version_from, source_commit_id, evidence_ref)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private final JdbcTemplate jdbc;

    public JdbcCanonFactStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(UUID projectId, UUID commitId, long canonVersion, UUID subjectId, FactProposal proposal) {
        UUID factId = UUID.randomUUID();
        jdbc.update(INSERT_SQL, factId, projectId, proposal.id(), normalizedText(proposal.factType()).toUpperCase(Locale.ROOT),
                subjectId, normalizedText(proposal.subject()), normalizedText(proposal.predicate()),
                normalizedText(proposal.object()), canonVersion, commitId, proposal.evidence());
        return factId;
    }

    @Override
    public void retire(UUID commitId, long replacementVersion) {
        retireCommit(jdbc, "story_fact", commitId, replacementVersion);
    }
}
