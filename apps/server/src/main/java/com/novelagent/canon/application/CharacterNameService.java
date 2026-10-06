package com.novelagent.canon.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.canon.api.CharacterNameResponse;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterNameService {
    private static final Pattern REFERENCE = Pattern.compile(
            "\\{\\{entity:([0-9a-fA-F-]{36}):(CANONICAL|NICKNAME|TITLE)}}");
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actor;
    private final StoryBibleVersionRepository bibles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CharacterNameService(NovelProjectRepository projects, CurrentActorProvider actor,
            StoryBibleVersionRepository bibles, JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.projects = projects;
        this.actor = actor;
        this.bibles = bibles;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<CharacterNameResponse> list(UUID projectId) {
        requireOwnedProject(projectId);
        return rows(projectId);
    }

    @Transactional
    public List<CharacterNameResponse> initializeFromStoryBible(UUID projectId) {
        requireOwnedProject(projectId);
        StoryBibleVersion bible = bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .orElseThrow(() -> new IllegalArgumentException("请先生成故事圣经"));
        return initializeFromStoryBible(projectId, bible);
    }

    @Transactional
    public List<CharacterNameResponse> initializeFromStoryBible(UUID projectId, StoryBibleVersion bible) {
        requireOwnedProject(projectId);
        if (!projectId.equals(bible.getProjectId())) throw new IllegalArgumentException("故事圣经不属于本项目");
        StoryBibleContent content = bible.getContent();
        Map<String, String> names = new LinkedHashMap<>();
        for (var blueprint : content.characterBlueprints()) {
            putName(names, "PROTAGONIST".equals(blueprint.role()) ? "PROTAGONIST"
                    : "BLUEPRINT_" + UUID.nameUUIDFromBytes(blueprint.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)), blueprint.name());
        }
        if (!names.containsKey("PROTAGONIST")) putName(names, "PROTAGONIST", extractName(content.protagonist()));
        for (int index = 0; index < content.supportingCharacters().size(); index++) {
            putName(names, "SUPPORTING_" + (index + 1), extractName(content.supportingCharacters().get(index)));
        }
        registerNames(projectId, names, "STORY_BIBLE:" + bible.getId());
        return rows(projectId);
    }

    @Transactional
    public void initializeFromBlueprints(UUID projectId, UUID taskId, List<com.novelagent.planning.domain.CharacterBlueprint> blueprints) {
        requireOwnedProject(projectId);
        Map<String, String> names = new LinkedHashMap<>();
        for (var blueprint : blueprints) {
            putName(names, "PROTAGONIST".equals(blueprint.role()) ? "PROTAGONIST"
                    : "BLUEPRINT_" + UUID.nameUUIDFromBytes(blueprint.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)), blueprint.name());
        }
        registerNames(projectId, names, "PREPARATION:" + taskId);
    }

    private void registerNames(UUID projectId, Map<String, String> names, String evidence) {
        names.forEach((role, name) -> {
            Integer existing = jdbc.queryForObject("""
                    SELECT count(*) FROM story_entity e WHERE e.project_id = ? AND e.entity_type = 'CHARACTER'
                    AND e.canon_version_to IS NULL AND (e.canonical_name = ? OR e.source_name = ?
                    OR EXISTS (SELECT 1 FROM entity_alias a WHERE a.entity_id = e.id AND a.alias = ? AND a.canon_version_to IS NULL))
                    """, Integer.class, projectId, name, name, name);
            if (existing != null && existing > 0) return;
            Integer occupied = jdbc.queryForObject("SELECT count(*) FROM story_entity WHERE project_id = ? AND role_key = ? AND canon_version_to IS NULL",
                    Integer.class, projectId, role);
            String availableRole = occupied != null && occupied > 0
                    ? "BLUEPRINT_" + UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : role;
            jdbc.update("""
                INSERT INTO story_entity(
                    id, project_id, entity_type, canonical_name, status, canon_version_from,
                    source_commit_id, evidence_ref, role_key, source_name)
                VALUES (?, ?, 'CHARACTER', ?, 'PLANNED', 0, NULL, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), projectId, name, evidence, availableRole, name);
        });
    }

    @Transactional
    public CharacterNameResponse update(UUID projectId, UUID entityId, long expectedVersion,
            String canonicalName, String nickname, String title) {
        requireOwnedProject(projectId);
        CharacterNameResponse before = row(projectId, entityId);
        if (before.version() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, before.version());
        }
        String name = required(canonicalName);
        try {
            int changed = jdbc.update("""
                    UPDATE story_entity
                    SET canonical_name = ?, nickname = ?, title_name = ?, row_version = row_version + 1
                    WHERE id = ? AND project_id = ? AND entity_type = 'CHARACTER'
                      AND canon_version_to IS NULL AND row_version = ?
                    """, name, optional(nickname), optional(title), entityId, projectId, expectedVersion);
            if (changed != 1) throw new ResourceVersionConflictException(expectedVersion, row(projectId, entityId).version());
        } catch (DuplicateKeyException exception) {
            throw new IllegalArgumentException("该项目已经存在同名人物");
        }
        if (!before.canonicalName().equals(name)) {
            jdbc.update("""
                    INSERT INTO entity_alias(id, project_id, entity_id, alias, alias_type,
                        canon_version_from, source_commit_id, evidence_ref)
                    VALUES (?, ?, ?, ?, 'FORMER_NAME', 0, NULL, 'CHARACTER_RENAME')
                    ON CONFLICT(entity_id, alias) DO NOTHING
                    """, UUID.randomUUID(), projectId, entityId, before.canonicalName());
        }
        return row(projectId, entityId);
    }

    @Transactional(readOnly = true)
    public String render(UUID projectId, String text) {
        List<CharacterNameResponse> characters = rows(projectId);
        return render(text, characters, nameForms(projectId, characters));
    }

    private String render(String text, List<CharacterNameResponse> characters, List<NameForm> forms) {
        if (text == null || text.isBlank()) return text;
        Map<UUID, CharacterNameResponse> byId = new LinkedHashMap<>();
        characters.forEach(item -> byId.put(item.id(), item));
        Matcher matcher = REFERENCE.matcher(text);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            CharacterNameResponse character = byId.get(UUID.fromString(matcher.group(1)));
            String replacement = character == null ? matcher.group() : display(character, matcher.group(2));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        String result = rendered.toString();
        for (NameForm form : forms) {
            CharacterNameResponse character = byId.get(form.id());
            if (character != null && !form.text().equals(display(character, form.form()))) {
                result = result.replace(form.text(), display(character, form.form()));
            }
        }
        return result;
    }

    @Transactional(readOnly = true)
    public ManuscriptContent tokenize(UUID projectId, ManuscriptContent content) {
        List<CharacterNameResponse> characters = rows(projectId);
        List<NameForm> forms = nameForms(projectId, characters);
        return new ManuscriptContent(tokenize(content.title(), forms), tokenize(content.body(), forms),
                tokenize(content.summary(), forms), content.continuityNotes().stream()
                        .map(value -> tokenize(value, forms)).toList());
    }

    @Transactional(readOnly = true)
    public ManuscriptContent render(UUID projectId, ManuscriptContent content) {
        List<CharacterNameResponse> characters = rows(projectId);
        List<NameForm> forms = nameForms(projectId, characters);
        return new ManuscriptContent(render(content.title(), characters, forms), render(content.body(), characters, forms),
                render(content.summary(), characters, forms), content.continuityNotes().stream()
                        .map(value -> render(value, characters, forms)).toList());
    }

    @Transactional(readOnly = true)
    public <T> T render(UUID projectId, T value, Class<T> type) {
        if (value == null) return null;
        JsonNode tree = objectMapper.valueToTree(value);
        List<CharacterNameResponse> characters = rows(projectId);
        renderTextNodes(tree, characters, nameForms(projectId, characters));
        try {
            return objectMapper.treeToValue(tree, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("人物名称渲染失败", exception);
        }
    }

    @Transactional(readOnly = true)
    public String tokenize(UUID projectId, String text) {
        List<CharacterNameResponse> characters = rows(projectId);
        return tokenize(text, nameForms(projectId, characters));
    }

    private String tokenize(String text, List<NameForm> forms) {
        if (text == null || text.isBlank()) return text;
        String result = text;
        for (NameForm form : forms) {
            if (!form.text().isBlank()) {
                result = result.replace(form.text(), "{{entity:" + form.id() + ":" + form.form() + "}}");
            }
        }
        return result;
    }

    private List<CharacterNameResponse> rows(UUID projectId) {
        return jdbc.query("""
                SELECT id, role_key, COALESCE(source_name, canonical_name) AS source_name,
                       canonical_name, nickname, title_name, row_version
                FROM story_entity
                WHERE project_id = ? AND entity_type = 'CHARACTER' AND canon_version_to IS NULL
                ORDER BY CASE WHEN role_key = 'PROTAGONIST' THEN 0 ELSE 1 END, role_key, canonical_name
                """, (rs, row) -> new CharacterNameResponse(rs.getObject("id", UUID.class),
                rs.getString("role_key"), rs.getString("source_name"), rs.getString("canonical_name"),
                rs.getString("nickname"), rs.getString("title_name"), rs.getLong("row_version")), projectId);
    }

    private CharacterNameResponse row(UUID projectId, UUID entityId) {
        return jdbc.query("""
                SELECT id, role_key, COALESCE(source_name, canonical_name) AS source_name,
                       canonical_name, nickname, title_name, row_version
                FROM story_entity WHERE id = ? AND project_id = ? AND entity_type = 'CHARACTER'
                  AND canon_version_to IS NULL
                """, (rs, row) -> new CharacterNameResponse(rs.getObject("id", UUID.class),
                rs.getString("role_key"), rs.getString("source_name"), rs.getString("canonical_name"),
                rs.getString("nickname"), rs.getString("title_name"), rs.getLong("row_version")), entityId, projectId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("人物不存在"));
    }

    private void renderTextNodes(JsonNode node, List<CharacterNameResponse> characters, List<NameForm> forms) {
        if (node instanceof ObjectNode object) {
            object.properties().forEach(entry -> {
                JsonNode child = entry.getValue();
                if (child.isTextual()) object.put(entry.getKey(), render(child.textValue(), characters, forms));
                else renderTextNodes(child, characters, forms);
            });
        } else if (node instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isTextual()) array.set(index, objectMapper.getNodeFactory().textNode(render(child.textValue(), characters, forms)));
                else renderTextNodes(child, characters, forms);
            }
        }
    }

    private List<NameForm> nameForms(UUID projectId, List<CharacterNameResponse> characters) {
        List<NameForm> forms = new ArrayList<>();
        for (CharacterNameResponse character : characters) {
            forms.add(new NameForm(character.canonicalName(), character.id(), "CANONICAL"));
            forms.add(new NameForm(character.sourceName(), character.id(), "CANONICAL"));
            if (character.nickname() != null) forms.add(new NameForm(character.nickname(), character.id(), "NICKNAME"));
            if (character.title() != null) forms.add(new NameForm(character.title(), character.id(), "TITLE"));
        }
        forms.addAll(jdbc.query("""
                SELECT entity_id, alias, alias_type FROM entity_alias
                WHERE project_id = ? AND canon_version_to IS NULL
                """, (rs, row) -> new NameForm(rs.getString("alias"), rs.getObject("entity_id", UUID.class),
                switch (rs.getString("alias_type")) {
                    case "NICKNAME" -> "NICKNAME";
                    case "TITLE" -> "TITLE";
                    default -> "CANONICAL";
                }), projectId));
        return forms.stream().filter(value -> value.text() != null && !value.text().isBlank()).distinct()
                .sorted(Comparator.comparingInt((NameForm value) -> value.text().length()).reversed()).toList();
    }

    private void requireOwnedProject(UUID projectId) {
        projects.findById(projectId).filter(project -> project.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private static String extractName(String value) {
        if (value == null) return null;
        String candidate = value.trim().split("[/：:]", 2)[0].trim();
        return candidate.length() >= 2 && candidate.length() <= 40 ? candidate : null;
    }
    private static void putName(Map<String, String> values, String role, String name) {
        if (name != null && !values.containsValue(name)) values.put(role, name);
    }
    private static String display(CharacterNameResponse value, String form) {
        return switch (form) {
            case "NICKNAME" -> value.nickname() == null ? value.canonicalName() : value.nickname();
            case "TITLE" -> value.title() == null ? value.canonicalName() : value.title();
            default -> value.canonicalName();
        };
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("标准姓名不能为空");
        return value.trim();
    }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private record NameForm(String text, UUID id, String form) {}
}
