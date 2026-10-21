package com.novelagent.writing.infrastructure;

import com.novelagent.platform.support.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.writing.application.FirstThreeChaptersModel;
import com.novelagent.writing.domain.FirstThreeChaptersBudget;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersSource;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class FirstThreeChaptersModelAdapter implements FirstThreeChaptersModel {
    private static final int MAX_OUTPUT = 6000;
    private static final String SYSTEM = AgentPromptDefaults.system("FIRST_THREE_CHAPTERS_REVIEW");
    private final StructuredModelGateway models;
    private final ModelContextProperties capacities;
    private final ObjectMapper mapper;
    private final JsonNode schema;
    public FirstThreeChaptersModelAdapter(StructuredModelGateway models, ModelContextProperties capacities, ObjectMapper mapper) {
        this.models = models; this.capacities = capacities; this.mapper = mapper;
        this.schema = Json.call(() -> mapper.readTree(SCHEMA), "前三章通读输出 Schema 配置无效");
    }
    @Override public FirstThreeChaptersBudget budget(FirstThreeChaptersSource source, ModelProvider provider, String instruction) {
        var capacity = capacities.require(provider);
        String complete = SYSTEM + user(source, instruction) + schema;
        int estimated = Math.max(MemoryBudgetAllocator.estimateTokens(complete),
                (int) Math.ceil(complete.getBytes(StandardCharsets.UTF_8).length / 2.0)) + 512;
        int limit = Math.max(0, capacity.getContextWindowTokens() - MAX_OUTPUT - capacity.getSafetyMarginTokens());
        return new FirstThreeChaptersBudget(estimated, MAX_OUTPUT, capacity.getContextWindowTokens(),
                capacity.getSafetyMarginTokens(), limit, estimated <= limit,
                provider == ModelProvider.LOCAL_TEMPLATE ? 0 : 1,
                "三章完整正文及开篇依据的保守Token估算，非真实usage或金额上限；不截断正文，不默认发送整份九步底稿或后续章详细计划。" );
    }
    @Override public FirstThreeChaptersContent check(FirstThreeChaptersSource source, ModelProvider provider, String instruction) {
        if (!source.available() || !budget(source, provider, instruction).fits())
            throw new IllegalArgumentException("无法在预算内完整读取三章");
        if (provider == ModelProvider.LOCAL_TEMPLATE) return new FirstThreeChaptersContent(
                "仅核对完整三章、来源与版本；未完成文学通读，未判断吸引力、因果或兑现。",
                Arrays.stream(FirstThreeChaptersContent.Dimension.values()).map(d -> new FirstThreeChaptersContent.Assessment(
                        d, FirstThreeChaptersContent.Status.NOT_ASSESSED, "本地规则无法作文学判断，需明确选择模型后检查。", List.of())).toList(), List.of());
        var result = new AtomicReference<FirstThreeChaptersContent>();
        models.request(source.projectId(), "FIRST_THREE_CHAPTERS_REVIEW", provider,
                SYSTEM, user(source, instruction), schema, "opening_review_v1", MAX_OUTPUT, CodexSessionPolicy.NEW_THREAD,
                output -> result.set(parse(output, source)));
        if (result.get() == null) throw new IllegalArgumentException("三章报告输出校验失败：未返回报告");
        return result.get();
    }

    private FirstThreeChaptersContent parse(String output, FirstThreeChaptersSource source) {
        try {
            var content = mapper.readValue(output, FirstThreeChaptersContent.class);
            if (content == null) throw new IllegalArgumentException("模型未返回通读报告");
            content.validate(source, false);
            return content;
        } catch (JsonProcessingException e) {
            String field = e instanceof JsonMappingException mapping ? mapping.getPath().stream()
                    .map(reference -> reference.getFieldName() == null ? "[" + reference.getIndex() + "]" : reference.getFieldName())
                    .collect(Collectors.joining(".")) : "";
            // Jackson messages can contain private prose; return only the field location.
            throw new IllegalArgumentException("三章报告输出校验失败：" + (field.isEmpty() ? "报告" : field)
                    + " 字段格式或类型不符合返回协议", e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("三章报告输出校验失败：" + e.getMessage(), e);
        }
    }
    private String user(FirstThreeChaptersSource source, String instruction) {
        var input = mapper.createObjectNode();
        input.put("strategy", source.strategy()); input.put("outline", source.outlineContext());
        input.put("storyBible", source.bibleContext()); input.put("style", source.styleContext());
        input.put("profiles", source.profileContext()); input.put("canonVersion", source.canonVersion());
        input.put("coverage", "完整第1至3章正文及计划；全书目标、人物设定和承诺仅作规划依据，不是已发生事实。"
                + "未提供第四章以后的详细计划及九步原始底稿，不评判这些资料是否落实；长期伏笔不要求前三章全部兑现。");
        input.put("reportFormat", "assessments 对八个维度各返回一条，不重复维度；同一维度的多个观察合并到 observation，"
                + "多处证据分别记录，每条 evidence 只填写 chapterNumber 和对应章正文中的连续逐字 quote，不混入位置说明。"
                + "证据不足使用 NOT_ASSESSED；issues 无明确问题则为空，不要求凑数。");
        input.put("authorInstruction", instruction == null ? "" : instruction);
        var chapters = input.putArray("completeChapters");
        for (var chapter : source.chapters()) {
            var item = chapters.addObject();
            item.put("chapterNumber", chapter.chapterNumber()); item.put("title", chapter.title());
            item.put("body", chapter.body()); item.put("manuscriptId", chapter.manuscriptId() == null ? "" : chapter.manuscriptId().toString());
            item.put("versionNumber", chapter.versionNumber()); item.put("rowVersion", chapter.rowVersion());
            item.put("status", chapter.status()); item.set("contract", mapper.valueToTree(chapter.contract()));
            item.put("contractStatus", chapter.contractStatus());
        }
        return input.toString();
    }
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["summary","assessments","issues"],"properties":{
              "summary":{"type":"string"},
              "assessments":{"type":"array","minItems":8,"maxItems":8,"items":{"type":"object","additionalProperties":false,
                "required":["dimension","status","observation","evidence"],"properties":{
                  "dimension":{"$ref":"#/$defs/dimension"},"status":{"type":"string","enum":["OBSERVATION","NOT_ASSESSED"]},
                  "observation":{"type":"string"},"evidence":{"$ref":"#/$defs/evidence"}}}},
              "issues":{"type":"array","maxItems":24,"items":{"type":"object","additionalProperties":false,
                "required":["id","dimension","description","suggestion","evidence"],"properties":{
                  "id":{"type":"string"},"dimension":{"$ref":"#/$defs/dimension"},"description":{"type":"string"},
                  "suggestion":{"type":"string"},"evidence":{"$ref":"#/$defs/evidence"}}}}},
              "$defs":{"dimension":{"type":"string","enum":["FIRST_CHAPTER","CAUSAL_CONTINUITY","PAYOFF","REPETITION","CHARACTER","STYLE","LOGIC","SCENE"]},
                "evidence":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["chapterNumber","quote"],
                  "properties":{"chapterNumber":{"type":"integer","enum":[1,2,3]},"quote":{"type":"string"}}}}}}
            """;
}
