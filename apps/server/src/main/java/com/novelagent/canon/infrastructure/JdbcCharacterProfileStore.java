package com.novelagent.canon.infrastructure;

import static com.novelagent.platform.support.Text.hasText;
import static com.novelagent.platform.support.Text.trimToNull;

import com.novelagent.canon.api.CharacterProfileResponse;
import com.novelagent.canon.api.UpdateCharacterProfileRequest;
import com.novelagent.canon.application.CharacterProfileStore;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCharacterProfileStore implements CharacterProfileStore {
    private static final String PROFILE_SELECT_SQL = """
            SELECT e.id, e.role_key, e.canonical_name,
                   p.gender, p.age_description, p.identity_text, p.appearance, p.background,
                   p.external_personality, p.internal_personality, p.core_desire, p.fear, p.flaw,
                   p.values_text, p.speech_style, p.behavior_habits, p.secret_text, p.character_arc,
                   p.behavior_boundaries, p.notes, COALESCE(p.row_version, 0) AS row_version
            FROM story_entity e
            LEFT JOIN character_profile p ON p.character_id = e.id
            WHERE e.project_id = ? AND e.entity_type = 'CHARACTER' AND e.canon_version_to IS NULL
            """;
    private static final String PROFILE_ORDER_SQL =
            " ORDER BY CASE WHEN e.role_key = 'PROTAGONIST' THEN 0 ELSE 1 END, e.role_key, e.canonical_name";
    private static final String REFERENCES_SQL = """
            SELECT e.id, e.role_key, e.canonical_name, e.source_name, e.nickname, e.title_name,
                   array_remove(array_agg(DISTINCT a.alias), NULL) AS aliases
            FROM story_entity e
            LEFT JOIN entity_alias a ON a.project_id = e.project_id AND a.entity_id = e.id
                AND a.canon_version_to IS NULL
            WHERE e.project_id = ? AND e.entity_type = 'CHARACTER' AND e.canon_version_to IS NULL
            GROUP BY e.id, e.role_key, e.canonical_name, e.source_name, e.nickname, e.title_name
            """;
    private static final String UPDATE_SQL = """
            UPDATE character_profile SET
                gender = ?, age_description = ?, identity_text = ?, appearance = ?, background = ?,
                external_personality = ?, internal_personality = ?, core_desire = ?, fear = ?, flaw = ?,
                values_text = ?, speech_style = ?, behavior_habits = ?, secret_text = ?, character_arc = ?,
                behavior_boundaries = ?, notes = ?, row_version = row_version + 1, updated_at = NOW()
            WHERE character_id = ? AND project_id = ? AND row_version = ?
            """;

    private final JdbcTemplate jdbc;

    public JdbcCharacterProfileStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void ensureMissingProfiles(UUID projectId) {
        jdbc.update("""
                INSERT INTO character_profile(character_id, project_id)
                SELECT id, project_id FROM story_entity
                WHERE project_id = ? AND entity_type = 'CHARACTER' AND canon_version_to IS NULL
                ON CONFLICT (character_id) DO NOTHING
                """, projectId);
    }

    @Override
    public void ensureProfile(UUID projectId, UUID characterId) {
        jdbc.update("""
                INSERT INTO character_profile(character_id, project_id) VALUES (?, ?)
                ON CONFLICT (character_id) DO NOTHING
                """, characterId, projectId);
    }

    @Override
    public boolean containsActiveCharacter(UUID projectId, UUID characterId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM story_entity
                WHERE id = ? AND project_id = ? AND entity_type = 'CHARACTER' AND canon_version_to IS NULL
                """, Integer.class, characterId, projectId);
        return count != null && count > 0;
    }

    @Override
    public int update(UUID projectId, UUID characterId, long expectedVersion, UpdateCharacterProfileRequest value) {
        return jdbc.update(UPDATE_SQL,
                trimToNull(value.gender()), trimToNull(value.ageDescription()), trimToNull(value.identity()),
                trimToNull(value.appearance()), trimToNull(value.background()), trimToNull(value.externalPersonality()),
                trimToNull(value.internalPersonality()), trimToNull(value.coreDesire()), trimToNull(value.fear()),
                trimToNull(value.flaw()), trimToNull(value.values()), trimToNull(value.speechStyle()),
                trimToNull(value.behaviorHabits()), trimToNull(value.secret()), trimToNull(value.characterArc()),
                trimToNull(value.behaviorBoundaries()), trimToNull(value.notes()), characterId, projectId, expectedVersion);
    }

    @Override
    public List<CharacterProfileResponse> profiles(UUID projectId) {
        return jdbc.query(PROFILE_SELECT_SQL + PROFILE_ORDER_SQL, (rs, number) -> map(rs), projectId);
    }

    @Override
    public List<CharacterProfileResponse> profiles(UUID projectId, Collection<UUID> characterIds) {
        if (characterIds.isEmpty()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        params.add(projectId);
        params.addAll(characterIds);
        String selection = " AND e.id IN (" + String.join(",", Collections.nCopies(characterIds.size(), "?")) + ")";
        return jdbc.query(PROFILE_SELECT_SQL + selection + PROFILE_ORDER_SQL, (rs, number) -> map(rs), params.toArray());
    }

    @Override
    public Optional<CharacterProfileResponse> find(UUID projectId, UUID characterId) {
        return jdbc.query(PROFILE_SELECT_SQL + " AND e.id = ?", (rs, number) -> map(rs), projectId, characterId)
                .stream().findFirst();
    }

    @Override
    public List<CharacterReference> references(UUID projectId) {
        return jdbc.query(REFERENCES_SQL, (rs, number) -> {
            List<String> forms = new ArrayList<>(Arrays.asList(rs.getString("canonical_name"),
                    rs.getString("source_name"), rs.getString("nickname"), rs.getString("title_name")));
            Array aliases = rs.getArray("aliases");
            if (aliases != null && aliases.getArray() instanceof String[] values) {
                forms.addAll(Arrays.asList(values));
            }
            return new CharacterReference(rs.getObject("id", UUID.class), rs.getString("role_key"),
                    forms.stream().filter(value -> hasText(value)).map(String::trim).distinct().toList());
        }, projectId);
    }

    private CharacterProfileResponse map(ResultSet rs) throws SQLException {
        return new CharacterProfileResponse(rs.getObject("id", UUID.class), rs.getString("role_key"),
                rs.getString("canonical_name"), rs.getString("gender"), rs.getString("age_description"),
                rs.getString("identity_text"), rs.getString("appearance"), rs.getString("background"),
                rs.getString("external_personality"), rs.getString("internal_personality"),
                rs.getString("core_desire"), rs.getString("fear"), rs.getString("flaw"),
                rs.getString("values_text"), rs.getString("speech_style"), rs.getString("behavior_habits"),
                rs.getString("secret_text"), rs.getString("character_arc"),
                rs.getString("behavior_boundaries"), rs.getString("notes"), rs.getLong("row_version"));
    }
}
