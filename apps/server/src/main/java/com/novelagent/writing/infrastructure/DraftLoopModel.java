package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.domain.*;
import java.util.function.Consumer;
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
        data.put("scope", "仅本章全文、实际提供的前两章参考和相关事实；sceneOutlineNeedsUpdate=true 的旧底稿不能当作有效必写计划。所有阶段共用本次冻结依据。");
        if (draft != null) data.set("currentDraft", mapper.valueToTree(draft));
        if (report != null) data.set("suggestionsNotFacts", mapper.valueToTree(report));
        gateway.request(run.getProjectId(), stage, run.getProvider(), AgentPromptDefaults.system(stage), data.toString(),
                schema, "draft_loop_" + run.getPhase().name().toLowerCase(java.util.Locale.ROOT), limit,
                CodexSessionPolicy.NEW_THREAD, output -> save.accept(parse(output, type)));
    }

    private <T> T parse(String output, Class<T> type) {
        try {
            T result = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(output);
            if (result == null) throw new IllegalArgumentException("自动编辑模型返回空结果");
            return result;
        } catch (JsonProcessingException failure) {
            throw new ModelProviderException("自动编辑输出协议错误：" + type.getSimpleName(), failure);
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
        for (var key : new String[] { "id", "description", "evidence", "existingBasis", "gap", "candidateDesign", "impact", "suggestion" }) text(item, key);
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
        var alternatives = fields.putObject("content").putArray("anyOf");
        alternatives.add(schemas.manuscript().path("properties").path("content")); alternatives.addObject().put("type", "null");
        fields.putObject("changeSummary").put("type", "array").putObject("items").put("type", "string");
        required(root, fields); return root;
    }
}
