package com.novelagent.chat.application;

import static com.novelagent.platform.support.Text.isBlank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.chat.domain.ChatSource;
import com.novelagent.planning.application.SnowflakeStep;
import com.novelagent.planning.domain.*;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.platform.support.Sha256;
import com.novelagent.project.application.ProjectAccessService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Project-scoped read targets and author-only revision adapters. No model-supplied table or column is executed. */
@Service
public class ChatMaterials {
    private record ProfileField(String column, String label, int limit) { }
    private static final Map<String, ProfileField> PROFILE_FIELDS = Map.ofEntries(
            field("identity_text", "身份", 3000), field("appearance", "外貌", 3000),
            field("background", "背景", 6000), field("external_personality", "外在性格", 4000),
            field("internal_personality", "内在性格", 4000), field("core_desire", "核心欲望", 3000),
            field("fear", "恐惧", 3000), field("flaw", "缺陷", 3000), field("values_text", "价值观", 3000),
            field("speech_style", "声口", 4000), field("behavior_habits", "行为习惯", 4000),
            field("secret_text", "秘密", 5000), field("character_arc", "人物弧线", 5000),
            field("behavior_boundaries", "行为边界", 5000), field("notes", "补充说明", 5000),
            field("gender", "性别", 40), field("age_description", "年龄", 100));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectAccessService access;
    private final OutlineVersionRepository outlines;

    public ChatMaterials(JdbcTemplate jdbc, ObjectMapper json, ProjectAccessService access, OutlineVersionRepository outlines) {
        this.jdbc = jdbc; this.json = json; this.access = access; this.outlines = outlines;
    }

    /** Only latest editable versions are listed; history cannot silently become the active writing basis. */
    public List<ChatSource> catalog(UUID projectId) {
        access.requireOwnedProject(projectId);
        String basis = basis(projectId);
        List<ChatSource> result = new ArrayList<>();
        for (var row : rows("""
                SELECT jsonb_build_object('id',id,'original_filename',original_filename,'extracted_text',extracted_text,
                  'sha256',sha256,'status',status)::text FROM work_import WHERE project_id=? ORDER BY created_at DESC
                """, projectId))
            result.add(source("IMPORT", row, "extracted_text", row.path("original_filename").asText(), false, basis));
        for (var row : rows("SELECT to_jsonb(t)::text FROM snowflake_planning_run t WHERE project_id=? ORDER BY created_at DESC,id DESC LIMIT 1", projectId)) {
            for (var step : SnowflakeStep.values()) if (row.path("steps").has(step.name()))
                result.add(source("SNOWFLAKE", row, step.name(), "雪花 · " + step.label(),
                        !row.path("status").asText().equals("RUNNING") && step != SnowflakeStep.CHARACTER_SETTINGS, basis));
        }
        for (var row : rows("""
                SELECT (to_jsonb(p) || jsonb_build_object('name',e.canonical_name))::text FROM character_profile p JOIN story_entity e ON e.id=p.character_id
                WHERE p.project_id=? AND e.entity_type='CHARACTER' AND e.canon_version_to IS NULL ORDER BY p.character_id
                """, projectId)) {
            String name = row.path("name").asText();
            PROFILE_FIELDS.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                    result.add(source("PROFILE", row, entry.getKey(), name + " · " + entry.getValue().label(), true, basis)));
        }
        for (var row : rows("SELECT to_jsonb(t)::text FROM outline_version t WHERE project_id=? ORDER BY generation_number DESC LIMIT 1", projectId)) {
            for (String field : List.of("premise", "structureSummary", "pacingStrategy"))
                result.add(source("OUTLINE", row, field, "大纲 · " + field, true, basis));
            for (var arc : row.path("content").path("arcs")) for (var chapter : arc.path("chapters"))
                result.add(source("OUTLINE", row, chapter.path("number").asText(),
                        "第" + chapter.path("number").asInt() + "章 · " + chapter.path("title").asText(),
                        !chapter.path("status").asText().equals("OCCURRED"), basis));
        }
        for (var row : rows("""
                SELECT to_jsonb(t)::text FROM manuscript_version t WHERE t.id IN
                  (SELECT DISTINCT ON (chapter_number) id FROM manuscript_version WHERE project_id=?
                   ORDER BY chapter_number,version_number DESC) ORDER BY t.chapter_number
                """, projectId))
            result.add(source("MANUSCRIPT", row, "content", "正文 第" + row.path("chapter_number").asInt()
                    + "章 · " + row.path("status").asText(), false, basis));
        for (var row : rows("SELECT to_jsonb(t)::text FROM reader_experience_plan t WHERE project_id=? AND NOT deleted ORDER BY created_at", projectId))
            result.add(source("LEDGER", row, "all", "伏笔/承诺 · " + row.path("title").asText(), false, basis));
        for (var row : rows("""
                SELECT to_jsonb(t)::text FROM story_bible_version t WHERE project_id=? AND id=
                  (SELECT current_bible_version_id FROM novel_project WHERE id=?)
                """, projectId, projectId))
            result.add(source("PLANNING", row, "content", "已确认规划资料（非正文事实）", false, basis));
        return List.copyOf(result);
    }

    public ChatSource require(UUID projectId, String key) {
        return catalog(projectId).stream().filter(value -> value.key().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("资料已变化或不属于当前项目，请重新读取"));
    }

    /** Called inside the change transaction, never during a model request. Locks both project and source row. */
    public ChatSource apply(UUID projectId, ChatSource expected, String replacement, ChatSource restore) {
        return apply(projectId, expected, replacement, restore, List.of());
    }

    public ChatSource apply(UUID projectId, ChatSource expected, String replacement, ChatSource restore,
            List<ProjectChatStore.Proposal> siblings) {
        access.requireOwnedProject(projectId);
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id=? FOR UPDATE", UUID.class, projectId);
        String table = switch (expected.kind()) {
            case "PROFILE" -> "character_profile";
            case "OUTLINE" -> "outline_version";
            case "SNOWFLAKE" -> "snowflake_planning_run";
            default -> throw new IllegalArgumentException("此资料只读，不能通过对话覆盖");
        };
        String idColumn = expected.kind().equals("PROFILE") ? "character_id" : "id";
        jdbc.queryForObject("SELECT " + idColumn + " FROM " + table + " WHERE " + idColumn
                + "=? AND project_id=? FOR UPDATE", UUID.class, UUID.fromString(expected.recordId()), projectId);
        var current = require(projectId, expected.key());
        checkCurrent(expected, current);
        if (restore == null) validateReplacement(current, replacement);
        for (var sibling : siblings) {
            if (!sibling.source().kind().equals(current.kind()) || !sibling.source().recordId().equals(current.recordId()))
                throw new IllegalArgumentException("修改组来源不一致");
            checkCurrent(sibling.source(), require(projectId, sibling.source().key()));
            if (restore == null) validateReplacement(sibling.source(), sibling.replacement());
        }
        String key;
        if (current.kind().equals("PROFILE")) {
            var field = PROFILE_FIELDS.get(current.field());
            jdbc.update("UPDATE character_profile SET " + field.column()
                    + "=?,row_version=row_version+1,updated_at=now() WHERE character_id=? AND project_id=?",
                    restore == null ? replacement : nullable(restore.snapshot().get(current.field())), UUID.fromString(current.recordId()), projectId);
            for (var sibling : siblings) jdbc.update("UPDATE character_profile SET "
                    + PROFILE_FIELDS.get(sibling.source().field()).column() + "=? WHERE character_id=? AND project_id=?",
                    restore == null ? sibling.replacement() : nullable(restore.snapshot().get(sibling.source().field())), UUID.fromString(current.recordId()), projectId);
            key = current.key();
        } else if (current.kind().equals("OUTLINE")) {
            var base = outlines.findByIdAndProjectId(UUID.fromString(current.recordId()), projectId).orElseThrow();
            var content = restore == null ? base.getContent() : convert(restore.snapshot().path("content"), OutlineContent.class);
            if (restore == null) {
                var edits = new ArrayList<>(siblings);
                edits.add(new ProjectChatStore.Proposal(current, replacement, "", ""));
                edits.sort(Comparator.comparingInt(p -> p.source().field().matches("[0-9]+") ? Integer.parseInt(p.source().field()) : 0));
                for (var edit : edits) content = patchOutline(json, content, edit.source().field(), edit.replacement());
            }
            UUID id = UUID.randomUUID();
            int generation = outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId).orElseThrow().getGenerationNumber() + 1;
            outlines.saveAndFlush(OutlineVersion.create(id, projectId, generation, "PROJECT_CHAT", "作者确认创作对话修改",
                    base.getSourceBibleVersionId(), base.getId(), base.getWordBudget(), content,
                    List.of(restore == null ? "创作对话修改；后续章节待核对" : "作者撤销创作对话修改")));
            key = "OUTLINE:" + id + ":" + current.field();
        } else {
            var base = current.snapshot();
            var previous = restore == null ? base : restore.snapshot();
            ObjectNode steps = previous.path("steps").deepCopy();
            if (restore == null) {
                var changed = SnowflakeStep.valueOf(current.field());
                steps.put(changed.name(), replacement);
                for (var step : SnowflakeStep.values()) if (step.ordinal() > changed.ordinal()) steps.remove(step.name());
            }
            UUID id = UUID.randomUUID();
            boolean complete = restore != null && previous.path("status").asText().equals("SUCCEEDED");
            JsonNode projection = steps.has("CHARACTER_SETTINGS") ? previous.get("prepared_materials") : null;
            jdbc.update("""
                    INSERT INTO snowflake_planning_run(id,project_id,mode,provider,input_snapshot,status,active_stage,
                        steps,core,characters,world,plot,prepared_materials,error_message)
                    VALUES (?,?,?,?,CAST(? AS jsonb),?,?,CAST(? AS jsonb),?,?,?,?,CAST(? AS jsonb),?)
                    """, id, projectId, previous.path("mode").asText(), previous.path("provider").asText(),
                    previous.path("input_snapshot").toString(), complete ? "SUCCEEDED" : "CANCELLED",
                    restore == null ? current.field() : previous.path("active_stage").asText(),
                    steps.toString(), nullable(steps.get("CORE")), nullable(steps.get("CHARACTER_SETTINGS")),
                    nullable(previous.get("world")), nullable(steps.get("DETAILED_OUTLINE")),
                    projection == null || projection.isNull() ? null : projection.toString(),
                    complete ? null : "作者已修改底稿，后续依赖步骤待生成；请在导入页继续并重新确认规划");
            key = "SNOWFLAKE:" + id + ":" + current.field();
        }
        return require(projectId, key);
    }

    public static void checkCurrent(ChatSource expected, ChatSource current) {
        if (!expected.writable() || !current.writable()) throw new IllegalArgumentException("此资料当前不可修改");
        if (!expected.fingerprint().equals(current.fingerprint()) || !expected.basis().equals(current.basis()))
            throw new IllegalArgumentException("修改依据已变化，请重新对话生成候选；不能覆盖新版本");
    }

    public static void validateReplacement(ChatSource source, String replacement) {
        int limit = source.kind().equals("PROFILE") ? PROFILE_FIELDS.get(source.field()).limit() : 60000;
        if (isBlank(replacement) || replacement.length() > limit)
            throw new IllegalArgumentException("替换内容为空或超出字段长度上限：" + limit);
        if (replacement.equals(source.text())) throw new IllegalArgumentException("候选与原文相同，无需修改");
    }

    /** Chapter blocks stay opaque prose; only dependent freshness metadata changes. */
    public static OutlineContent patchOutline(ObjectMapper json, OutlineContent content, String field, String text) {
        ObjectNode copy = json.valueToTree(content);
        boolean book = List.of("premise", "structureSummary", "pacingStrategy").contains(field);
        if (book) copy.put(field, text);
        int number = book ? 0 : Integer.parseInt(field);
        boolean found = book;
        for (var arc : copy.path("arcs")) for (var node : arc.path("chapters")) {
            ObjectNode chapter = (ObjectNode) node;
            int n = chapter.path("number").asInt();
            if (n == number) {
                if (chapter.path("status").asText().equals("OCCURRED")) throw new IllegalArgumentException("已发生章节不能覆盖");
                chapter.put("content", text).put("contentNeedsUpdate", false); found = true;
            } else if ((book || n > number) && !chapter.path("status").asText().equals("OCCURRED"))
                chapter.put("contentNeedsUpdate", true);
        }
        if (!found) throw new IllegalArgumentException("章节不存在");
        try { return json.treeToValue(copy, OutlineContent.class); }
        catch (Exception failure) { throw new IllegalArgumentException("大纲版本无法保存", failure); }
    }

    private ChatSource source(String kind, JsonNode row, String field, String title, boolean writable, String basis) {
        String id = row.path(kind.equals("PROFILE") ? "character_id" : "id").asText();
        JsonNode value = switch (kind) {
            case "SNOWFLAKE" -> row.path("steps").path(field);
            case "OUTLINE" -> {
                if (!field.matches("[0-9]+")) yield row.path("content").path(field);
                JsonNode chapter = null;
                for (var arc : row.path("content").path("arcs")) for (var c : arc.path("chapters"))
                    if (c.path("number").asText().equals(field)) chapter = c.path("content");
                yield chapter;
            }
            case "LEDGER" -> row;
            default -> row.path(field);
        };
        String text = value == null || value.isNull() ? "" : value.isTextual() ? value.asText() : value.toPrettyString();
        return new ChatSource(kind + ":" + id + ":" + field, title, text, writable,
                Sha256.ofUtf8(row.toString()), basis, kind, id, field, row);
    }

    private String basis(UUID id) {
        return Sha256.ofUtf8(jdbc.queryForObject("""
                SELECT jsonb_build_object('canon',current_canon_version,'planning',current_bible_version_id,
                  'outline',current_outline_version_id,'settings',settings)::text FROM novel_project WHERE id=?
                """, String.class, id));
    }
    private List<JsonNode> rows(String sql, Object... args) { return jdbc.query(sql, (rs, n) -> parse(rs.getString(1)), args); }
    private JsonNode parse(String text) {
        try { return json.readTree(text); } catch (Exception e) { throw new IllegalStateException("资料记录无法读取", e); }
    }
    private <T> T convert(JsonNode node, Class<T> type) {
        try { return json.treeToValue(node, type); } catch (Exception e) { throw new IllegalStateException("资料记录无法读取", e); }
    }
    private static String nullable(JsonNode node) { return node == null || node.isNull() ? null : node.asText(); }
    private static Map.Entry<String, ProfileField> field(String column, String label, int limit) {
        return Map.entry(column, new ProfileField(column, label, limit));
    }
}
