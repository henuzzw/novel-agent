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
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
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

/**
 * 人物命名。
 *
 * <p>管理稳定人物身份及姓名、昵称、称谓的显示映射。内容中的实体占位符可按当前姓名渲染，反向标记需避免歧义；姓名初始化不等于正文已发生事实。</p>
 */
@Service
public class CharacterNameService {
    private static final Pattern REFERENCE = Pattern.compile(
            "\\{\\{entity:([0-9a-fA-F-]{36}):(CANONICAL|NICKNAME|TITLE)}}");
    private final ProjectAccessService access;
    private final StoryBibleVersionRepository bibles;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CharacterNameService(
            ProjectAccessService access,
            StoryBibleVersionRepository bibles,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.access = access;
        this.bibles = bibles;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<CharacterNameResponse> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return rows(projectId);
    }

    /**
     * 从指定圣经的蓝图及兼容人物描述幂等建立规划人物身份，保留已有名称及别名。仅传项目 ID 时读取最新保存的圣经；传入圣经版本时严格核对项目归属。此步骤不补齐独立档案，也不写正文事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional
    public List<CharacterNameResponse> initializeFromStoryBible(UUID projectId) {
        access.requireOwnedProject(projectId);
        StoryBibleVersion bible = bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .orElseThrow(() -> new IllegalArgumentException("请先生成故事圣经"));
        return initializeFromStoryBible(projectId, bible);
    }

    /**
     * 从指定圣经的蓝图及兼容人物描述幂等建立规划人物身份，保留已有名称及别名。仅传项目 ID 时读取最新保存的圣经；传入圣经版本时严格核对项目归属。此步骤不补齐独立档案，也不写正文事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param bible 指定圣经来源或其内容，不隐式使用其他最新草稿。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional
    public List<CharacterNameResponse> initializeFromStoryBible(UUID projectId, StoryBibleVersion bible) {
        access.requireOwnedProject(projectId);
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

    /**
     * 按创作准备蓝图建立稳定人物身份，并保留已有命名；蓝图是规划来源而非正文事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param taskId 创作准备等来源任务 ID。
     * @param blueprints 待应用或补全的结构化人物蓝图。
     */
    @Transactional
    public void initializeFromBlueprints(UUID projectId, UUID taskId, List<com.novelagent.planning.domain.CharacterBlueprint> blueprints) {
        access.requireOwnedProject(projectId);
        Map<String, String> names = new LinkedHashMap<>();
        for (var blueprint : blueprints) {
            putName(names, "PROTAGONIST".equals(blueprint.role()) ? "PROTAGONIST"
                    : "BLUEPRINT_" + UUID.nameUUIDFromBytes(blueprint.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)), blueprint.name());
        }
        registerNames(projectId, names, "PREPARATION:" + taskId);
    }

    /**
     * 按标准名、来源名及有效别名检查已有人物，避免重复登记；角色键已占用时使用基于姓名的稳定键。新记录标记 PLANNED，带规划证据来源，不带正史提交。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param names 来源人物姓名与角色键映射，用于幂等登记规划身份。
     * @param evidence 来源中的连续原文证据，不允许拼接或伪造引文。
     */
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

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param canonicalName 当前显示标准名，不替换稳定人物 ID。
     * @param nickname 作者指定昵称，可为空。
     * @param title 作者指定称谓或当前记录标题，含义见业务对象。
     */
    @Transactional
    public CharacterNameResponse update(UUID projectId, UUID entityId, long expectedVersion,
            String canonicalName, String nickname, String title) {
        access.requireOwnedProject(projectId);
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

    /**
     * 先按稳定实体占位符渲染当前姓名，再兼容替换已有原名及别名；对象重载递归处理文本节点。只是内容转换，不保存新版本；旧文本替换基于字符串匹配，不是语义消歧。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     */
    @Transactional(readOnly = true)
    public String render(UUID projectId, String text) {
        List<CharacterNameResponse> characters = rows(projectId);
        return render(text, characters, nameForms(projectId, characters));
    }

    /**
     * 先按稳定实体占位符渲染当前姓名，再兼容替换已有原名及别名；对象重载递归处理文本节点。只是内容转换，不保存新版本；旧文本替换基于字符串匹配，不是语义消歧。
     *
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     * @param characters 本次读取的人物命名快照，所有文本转换使用同一映射。
     * @param forms 已按长度排序的标准名、旧名及别名映射。
     */
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

    /**
     * 按长度优先的已有人名及别名映射，把正文文本转换为稳定实体占位符，便于后续改名。此转换使用字符串匹配，不自动解决同名歧义，也不创建人物事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional(readOnly = true)
    public ManuscriptContent tokenize(UUID projectId, ManuscriptContent content) {
        List<CharacterNameResponse> characters = rows(projectId);
        List<NameForm> forms = nameForms(projectId, characters);
        return new ManuscriptContent(tokenize(content.title(), forms), tokenize(content.body(), forms),
                tokenize(content.summary(), forms), content.continuityNotes().stream()
                        .map(value -> tokenize(value, forms)).toList());
    }

    /**
     * 先按稳定实体占位符渲染当前姓名，再兼容替换已有原名及别名；对象重载递归处理文本节点。只是内容转换，不保存新版本；旧文本替换基于字符串匹配，不是语义消歧。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional(readOnly = true)
    public ManuscriptContent render(UUID projectId, ManuscriptContent content) {
        List<CharacterNameResponse> characters = rows(projectId);
        List<NameForm> forms = nameForms(projectId, characters);
        return new ManuscriptContent(render(content.title(), characters, forms), render(content.body(), characters, forms),
                render(content.summary(), characters, forms), content.continuityNotes().stream()
                        .map(value -> render(value, characters, forms)).toList());
    }

    /**
     * 先按稳定实体占位符渲染当前姓名，再兼容替换已有原名及别名；对象重载递归处理文本节点。只是内容转换，不保存新版本；旧文本替换基于字符串匹配，不是语义消歧。
     *
     * @param <T> 返回对象的目标类型；仅对其文本内容执行人物名称渲染。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     * @param type JSON 转换或反序列化的目标 Java 类型。
     */
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

    /**
     * 按长度优先的已有人名及别名映射，把正文文本转换为稳定实体占位符，便于后续改名。此转换使用字符串匹配，不自动解决同名歧义，也不创建人物事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     */
    @Transactional(readOnly = true)
    public String tokenize(UUID projectId, String text) {
        List<CharacterNameResponse> characters = rows(projectId);
        return tokenize(text, nameForms(projectId, characters));
    }

    /**
     * 按长度优先的已有人名及别名映射，把正文文本转换为稳定实体占位符，便于后续改名。此转换使用字符串匹配，不自动解决同名歧义，也不创建人物事实。
     *
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     * @param forms 已按长度排序的标准名、旧名及别名映射。
     */
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

    /**
     * 按项目读取尚未失效的人物命名记录，主角优先；source_name 缺失时使用标准名兼容历史数据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
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

    /**
     * 在项目内读取指定有效人物并映射姓名及行版本；找不到时拒绝修改，不跨项目查找同 ID。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     */
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

    /**
     * 递归遍历对象及数组中的文本节点，按同一姓名快照渲染；非文本值和对象结构保持不变。
     *
     * @param node 递归处理的 JSON 节点，只有文本节点发生名称转换。
     * @param characters 本次读取的人物命名快照，所有文本转换使用同一映射。
     * @param forms 已按长度排序的标准名、旧名及别名映射。
     */
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

    /**
     * 合并人物标准名、来源名、昵称、称谓及有效别名，过滤空值并按名称长度倒序，降低短名称先替换造成的干扰。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characters 本次读取的人物命名快照，所有文本转换使用同一映射。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
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
