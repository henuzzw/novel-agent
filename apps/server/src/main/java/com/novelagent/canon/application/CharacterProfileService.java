package com.novelagent.canon.application;

import com.novelagent.canon.api.CharacterProfileResponse;
import com.novelagent.canon.api.UpdateCharacterProfileRequest;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterProfileService {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actor;
    private final JdbcTemplate jdbc;

    public CharacterProfileService(NovelProjectRepository projects, CurrentActorProvider actor, JdbcTemplate jdbc) {
        this.projects = projects;
        this.actor = actor;
        this.jdbc = jdbc;
    }

    @Transactional
    public List<CharacterProfileResponse> list(UUID projectId) {
        requireOwnedProject(projectId);
        jdbc.update("""
                INSERT INTO character_profile(character_id, project_id)
                SELECT id, project_id FROM story_entity
                WHERE project_id = ? AND entity_type = 'CHARACTER' AND canon_version_to IS NULL
                ON CONFLICT (character_id) DO NOTHING
                """, projectId);
        return rows(projectId);
    }

    @Transactional
    public CharacterProfileResponse update(UUID projectId, UUID characterId, long expectedVersion,
            UpdateCharacterProfileRequest value) {
        requireOwnedProject(projectId);
        ensureCharacter(projectId, characterId);
        jdbc.update("""
                INSERT INTO character_profile(character_id, project_id) VALUES (?, ?)
                ON CONFLICT (character_id) DO NOTHING
                """, characterId, projectId);
        int changed = jdbc.update("""
                UPDATE character_profile SET
                    gender = ?, age_description = ?, identity_text = ?, appearance = ?, background = ?,
                    external_personality = ?, internal_personality = ?, core_desire = ?, fear = ?, flaw = ?,
                    values_text = ?, speech_style = ?, behavior_habits = ?, secret_text = ?, character_arc = ?,
                    behavior_boundaries = ?, notes = ?, row_version = row_version + 1, updated_at = NOW()
                WHERE character_id = ? AND project_id = ? AND row_version = ?
                """, optional(value.gender()), optional(value.ageDescription()), optional(value.identity()),
                optional(value.appearance()), optional(value.background()), optional(value.externalPersonality()),
                optional(value.internalPersonality()), optional(value.coreDesire()), optional(value.fear()),
                optional(value.flaw()), optional(value.values()), optional(value.speechStyle()),
                optional(value.behaviorHabits()), optional(value.secret()), optional(value.characterArc()),
                optional(value.behaviorBoundaries()), optional(value.notes()), characterId, projectId, expectedVersion);
        if (changed != 1) {
            throw new ResourceVersionConflictException(expectedVersion, row(projectId, characterId).version());
        }
        return row(projectId, characterId);
    }

    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        List<CharacterProfileResponse> profiles = rows(projectId).stream().filter(this::hasDetails).toList();
        if (profiles.isEmpty()) return "暂无单独配置的人物档案";
        StringBuilder result = new StringBuilder();
        for (CharacterProfileResponse profile : profiles) {
            if (!result.isEmpty()) result.append('\n');
            result.append("人物：").append(profile.canonicalName());
            append(result, "性别", profile.gender());
            append(result, "年龄", profile.ageDescription());
            append(result, "身份", profile.identity());
            append(result, "外貌", profile.appearance());
            append(result, "背景", profile.background());
            append(result, "外在性格", profile.externalPersonality());
            append(result, "内在性格", profile.internalPersonality());
            append(result, "核心欲望", profile.coreDesire());
            append(result, "恐惧", profile.fear());
            append(result, "缺陷", profile.flaw());
            append(result, "价值观", profile.values());
            append(result, "说话方式", profile.speechStyle());
            append(result, "行为习惯", profile.behaviorHabits());
            append(result, "秘密", profile.secret());
            append(result, "人物弧光", profile.characterArc());
            append(result, "行为边界", profile.behaviorBoundaries());
            append(result, "补充备注", profile.notes());
        }
        return result.toString();
    }

    private boolean hasDetails(CharacterProfileResponse value) {
        return Arrays.asList(value.gender(), value.ageDescription(), value.identity(), value.appearance(), value.background(),
                value.externalPersonality(), value.internalPersonality(), value.coreDesire(), value.fear(), value.flaw(),
                value.values(), value.speechStyle(), value.behaviorHabits(), value.secret(), value.characterArc(),
                value.behaviorBoundaries(), value.notes()).stream().anyMatch(CharacterProfileService::hasText);
    }

    private List<CharacterProfileResponse> rows(UUID projectId) {
        return jdbc.query(selectSql() + " ORDER BY CASE WHEN e.role_key = 'PROTAGONIST' THEN 0 ELSE 1 END, e.role_key, e.canonical_name",
                (rs, number) -> map(rs), projectId);
    }

    private CharacterProfileResponse row(UUID projectId, UUID characterId) {
        return jdbc.query(selectSql() + " AND e.id = ?", (rs, number) -> map(rs), projectId, characterId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("人物档案不存在"));
    }

    private String selectSql() {
        return """
                SELECT e.id, e.role_key, e.canonical_name,
                       p.gender, p.age_description, p.identity_text, p.appearance, p.background,
                       p.external_personality, p.internal_personality, p.core_desire, p.fear, p.flaw,
                       p.values_text, p.speech_style, p.behavior_habits, p.secret_text, p.character_arc,
                       p.behavior_boundaries, p.notes, COALESCE(p.row_version, 0) AS row_version
                FROM story_entity e
                LEFT JOIN character_profile p ON p.character_id = e.id
                WHERE e.project_id = ? AND e.entity_type = 'CHARACTER' AND e.canon_version_to IS NULL
                """;
    }

    private CharacterProfileResponse map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CharacterProfileResponse(rs.getObject("id", UUID.class), rs.getString("role_key"),
                rs.getString("canonical_name"), rs.getString("gender"), rs.getString("age_description"),
                rs.getString("identity_text"), rs.getString("appearance"), rs.getString("background"),
                rs.getString("external_personality"), rs.getString("internal_personality"),
                rs.getString("core_desire"), rs.getString("fear"), rs.getString("flaw"),
                rs.getString("values_text"), rs.getString("speech_style"), rs.getString("behavior_habits"),
                rs.getString("secret_text"), rs.getString("character_arc"),
                rs.getString("behavior_boundaries"), rs.getString("notes"), rs.getLong("row_version"));
    }

    private void ensureCharacter(UUID projectId, UUID characterId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM story_entity
                WHERE id = ? AND project_id = ? AND entity_type = 'CHARACTER' AND canon_version_to IS NULL
                """, Integer.class, characterId, projectId);
        if (count == null || count == 0) throw new IllegalArgumentException("人物不存在");
    }

    private void requireOwnedProject(UUID projectId) {
        projects.findById(projectId).filter(project -> project.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private static void append(StringBuilder target, String label, String value) {
        if (hasText(value)) target.append("；").append(label).append("：").append(value.trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String optional(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
