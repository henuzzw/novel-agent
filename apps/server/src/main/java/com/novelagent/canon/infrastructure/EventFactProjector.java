package com.novelagent.canon.infrastructure;

import static com.novelagent.canon.infrastructure.CanonFactProjectionText.factSentence;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.normalizedText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.payloadText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.retireCommit;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.truncate;

import com.novelagent.canon.application.CanonFactProjector;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class EventFactProjector implements CanonFactProjector {
    private static final String INSERT_SQL = """
        INSERT INTO story_event
            (id, project_id, source_fact_id, title, summary, story_time_text, narrative_chapter,
             canon_version_from, source_commit_id, evidence_ref)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private final JdbcTemplate jdbc;

    public EventFactProjector(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> factTypes() {
        return Set.of("EVENT", "EVENT_CREATE");
    }

    @Override
    public boolean requiresSubject(FactProposal proposal) {
        return !"EVENT_CREATE".equalsIgnoreCase(proposal.factType().trim());
    }

    @Override
    public void project(Projection projection) {
        FactProposal proposal = projection.proposal();
        TypedFactPayload payload = proposal.payload();
        String title = payloadText(payload, TypedFactPayload::eventTitle, normalizedText(proposal.subject()) + normalizedText(proposal.predicate()));
        String summary = payloadText(payload, TypedFactPayload::eventSummary, factSentence(proposal));
        String storyTime = payload == null ? null : payload.storyTime();
        jdbc.update(INSERT_SQL, UUID.randomUUID(), projection.projectId(), projection.factId(), truncate(title, 300), summary,
                storyTime, projection.chapterNumber(), projection.canonVersion(), projection.commitId(), proposal.evidence());
    }

    @Override
    public void retire(UUID commitId, long replacementVersion) {
        retireCommit(jdbc, "story_event", commitId, replacementVersion);
    }
}
