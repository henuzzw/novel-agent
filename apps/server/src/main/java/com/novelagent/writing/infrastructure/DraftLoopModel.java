package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.domain.*;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** 共用网关记录实际 Prompt、响应、错误与用量；B/C 独立会话，处理输出仍处于可追踪任务内。 */
@Component
public class DraftLoopModel {
    private final StructuredModelGateway gateway;
    private final ObjectMapper mapper;
    private final WritingOutputSchemas schemas;

    public DraftLoopModel(StructuredModelGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway; this.mapper = mapper; this.schemas = new WritingOutputSchemas(mapper);
    }

    public void write(DraftLoopRun run, Consumer<GeneratedManuscript> save) {
        call(run, "MANUSCRIPT", "根据冻结的创作依据创作本章完整正文。", null, null, schemas.manuscript(),
                16000, GeneratedManuscript.class, output -> {
                    DraftJudgment.requireContent(output.content());
                    if (output.changeSummary() == null) throw new IllegalArgumentException("写作输出缺少修改说明");
                    save.accept(output);
                });
    }

    public void check(DraftLoopRun run, ManuscriptContent draft, Consumer<DraftCheck> save) {
        call(run, "QUALITY_REVIEW", "独立检查当前完整草稿，不读取上一轮意见，不作数值评分。"
                + "existingBasis 仅列已有依据；gap、candidateDesign、impact 如无补丁填空字符串。"
                + "每条意见的evidence是1至6段独立引文，每段quote只写当前正文中的连续逐字原文。"
                + "对比不相邻内容时分成不同记录，不能拼接成一段；不得在quote中添加‘正文引文一’等标签、解释或省略号。"
                + "只读取本章全文与实际提供的前文，不能宣称读过未提供章节。", draft, null, checkSchema(),
                8000, DraftCheck.class, output -> { output.requireEvidenceIn(draft.body()); save.accept(output); });
    }

    public void judge(DraftLoopRun run, ManuscriptContent draft, DraftCheck report, Consumer<DraftJudgment> save) {
        call(run, "DRAFT_JUDGE_REVISION", "裁决本轮 B，按系统独立提供的冻结资料核对，再执行权限内修订。"
                + "不新增人工确认节点；资料或权限不足暂缓，不猜造补丁。", draft, report,
                judgmentSchema(), 16000, DraftJudgment.class, output -> { output.requireCoverage(report); save.accept(output); });
    }

    private <T> void call(DraftLoopRun run, String stage, String task, ManuscriptContent draft, DraftCheck report,
            JsonNode schema, int limit, Class<T> type, Consumer<T> save) {
        var data = mapper.createObjectNode();
        data.put("task", task); data.put("editorialRunId", run.getId().toString());
        data.put("round", run.getPhase() == DraftLoopRun.Phase.C ? run.getRounds().size() : run.getRounds().size() + 1);
        data.put("frozenSourceFingerprint", run.getBasis().fingerprint());
        data.put("independentWritingBasis", run.getBasis().prompt());
        data.put("scope", "仅本章全文、实际提供的前两章参考和相关事实；本章content是完整规划文字块，含场景与字数要求。所有阶段共用本次冻结依据，不把未来计划当作已发生事实。");
        if (draft != null) data.set("currentDraft", mapper.valueToTree(draft));
        if (report != null) data.set("suggestionsNotFacts", mapper.valueToTree(report));
        // Project boundaries already live in the frozen basis; do not append current mutable choices.
        String name = "draft_loop_" + run.getPhase().name().toLowerCase(Locale.ROOT);
        if (run.getPhase() == DraftLoopRun.Phase.A) {
            gateway.requestWithBoundaries(run.getProjectId(), stage, run.getProvider(), AgentPromptDefaults.system(stage), data.toString(),
                    schema, name, limit, CodexSessionPolicy.NEW_THREAD, output -> save.accept(parse(output, type)), "");
        } else {
            gateway.requestJsonWithBoundaries(run.getProjectId(), stage, run.getProvider(), AgentPromptDefaults.system(stage), data.toString(),
                    schema, name, limit, CodexSessionPolicy.NEW_THREAD, output -> save.accept(parse(output, type)), "");
        }
    }

    private <T> T parse(String output, Class<T> type) {
        try {
            T result = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(output);
            if (result == null) throw new IllegalArgumentException("自动编辑模型返回空结果");
            return result;
        } catch (JsonProcessingException failure) {
            String field = failure instanceof JsonMappingException mapping
                    ? mapping.getPath().stream().map(reference -> reference.getFieldName() != null
                            ? reference.getFieldName() : "[" + reference.getIndex() + "]")
                            .collect(Collectors.joining(".")) : "";
            String detail = failure.getCause() instanceof IllegalArgumentException invalid
                    ? invalid.getMessage() : "字段格式或类型不符合返回协议";
            // Jackson's full message may contain manuscript text; expose field paths and domain errors only.
            throw new IllegalArgumentException("自动编辑输出协议错误：" + type.getSimpleName()
                    + (field.isEmpty() ? "" : " · " + field) + "：" + detail, failure);
        }
    }

    private ObjectNode object() { return mapper.createObjectNode().put("type", "object").put("additionalProperties", false); }
    private void text(ObjectNode properties, String name) { properties.putObject(name).put("type", "string"); }
    private void enumeration(ObjectNode properties, String name, String... values) {
        var field = properties.putObject(name).put("type", "string");
        var list = field.putArray("enum"); for (var value : values) list.add(value);
    }
    private void required(ObjectNode schema, ObjectNode properties) {
        var array = schema.putArray("required"); properties.fieldNames().forEachRemaining(array::add);
    }

    JsonNode checkSchema() {
        var root = object(); var fields = root.putObject("properties"); text(fields, "summary");
        var issue = object(); var item = issue.putObject("properties");
        for (var key : new String[] { "id", "description", "existingBasis", "gap", "candidateDesign", "impact", "suggestion" }) text(item, key);
        var evidence = object(); var quote = evidence.putObject("properties"); text(quote, "quote"); required(evidence, quote);
        item.putObject("evidence").put("type", "array").put("minItems", 1)
                .put("maxItems", DraftCheck.MAX_EVIDENCE_QUOTES).set("items", evidence);
        enumeration(item, "category", "STYLE", "FLUENCY", "LOGIC", "SCENE"); required(issue, item);
        fields.putObject("issues").put("type", "array").put("maxItems", 20).set("items", issue);
        required(root, fields); return root;
    }

    JsonNode judgmentSchema() {
        var root = object(); var fields = root.putObject("properties");
        enumeration(fields, "action", "REVISED", "NO_CHANGE", "NEEDS_CONTEXT");
        var decision = object(); var item = decision.putObject("properties");
        text(item, "issueId"); enumeration(item, "verdict", "ACCEPT", "REJECT", "DEFER"); text(item, "reason"); required(decision, item);
        fields.putObject("decisions").put("type", "array").put("maxItems", 20).set("items", decision);
        // NO_CHANGE and NEEDS_CONTEXT return null; a revised manuscript remains one complete text block.
        ObjectNode content = schemas.manuscript().path("properties").path("content").deepCopy();
        content.putArray("type").add("object").add("null");
        fields.set("content", content);
        fields.putObject("changeSummary").put("type", "array").putObject("items").put("type", "string");
        required(root, fields); return root;
    }
}
