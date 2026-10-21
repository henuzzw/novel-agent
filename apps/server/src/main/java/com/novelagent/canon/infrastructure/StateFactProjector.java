package com.novelagent.canon.infrastructure;

import static com.novelagent.platform.support.Text.hasText;
import static com.novelagent.canon.infrastructure.CanonFactProjectionText.normalizedText;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CanonFactProjector;
import com.novelagent.platform.support.Json;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class StateFactProjector implements CanonFactProjector {
    private static final Map<String, String> STATE_FIELDS = Map.ofEntries(
            Map.entry("位置", "character.location"),
            Map.entry("所在地", "character.location"),
            Map.entry("身体状态", "character.physical_state"),
            Map.entry("健康状态", "character.physical_state"),
            Map.entry("情绪", "character.emotional_state"),
            Map.entry("心理状态", "character.emotional_state"),
            Map.entry("目标", "character.current_goal"),
            Map.entry("当前目标", "character.current_goal"),
            Map.entry("生死状态", "character.alive_status"),
            Map.entry("存活状态", "character.alive_status"));

    private static final String INSERT_SQL = """
        INSERT INTO entity_state_change
            (id, project_id, entity_id, source_fact_id, field_key, before_value, after_value,
             story_time_text, narrative_chapter, canon_version_from, source_commit_id, evidence_ref, created_at)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, clock_timestamp())
        """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StateFactProjector(
            JdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public Set<String> factTypes() {
        return Set.of("STATE", "STATE_CHANGE");
    }

    @Override
    public void project(Projection projection) {
        var field = stateField(projection.proposal());
        if (field.isEmpty()) {
            return;
        }
        insertState(projection, field.get());
    }

    private void insertState(Projection projection, String field) {
        FactProposal proposal = projection.proposal();
        TypedFactPayload payload = proposal.payload();
        String beforeValue = payload == null ? null : payload.beforeValue();
        String afterValue = payload != null && hasText(payload.afterValue())
                ? payload.afterValue() : normalizedText(proposal.object());
        String storyTime = payload == null ? null : payload.storyTime();
        // Transaction-wide now() would tie successive changes and make the chapter's final state ambiguous.
        jdbc.update(INSERT_SQL, UUID.randomUUID(), projection.projectId(), projection.subjectId(), projection.factId(), field,
                beforeValue == null ? null : json(beforeValue), json(afterValue), storyTime,
                projection.chapterNumber(), projection.canonVersion(), projection.commitId(), proposal.evidence());
    }

    private Optional<String> stateField(String predicate) {
        String value = normalizedText(predicate);
        return STATE_FIELDS.entrySet().stream()
                .filter(entry -> value.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private Optional<String> stateField(FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        if (payload != null && hasText(payload.fieldKey())) {
            return Optional.of(payload.fieldKey());
        }
        return stateField(proposal.predicate());
    }

    private String json(String value) {
        return Json.call(() -> mapper.writeValueAsString(value), "正史状态值无法序列化", IllegalArgumentException::new);
    }

    @Override
    public void retire(UUID commitId, long replacementVersion) {
        jdbc.update("UPDATE entity_state_change SET canon_version_to = ? "
                + "WHERE source_commit_id = ? AND canon_version_to IS NULL", replacementVersion, commitId);
    }
}
