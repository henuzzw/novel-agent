package com.novelagent.ingest.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ImportAnalysisPrompt {
    public static final String SYSTEM = com.novelagent.prompt.application.AgentPromptDefaults.system("IMPORT_SOURCE_ANALYSIS");
    public static final String WORLD_ASSESSMENT_RULES = "\n【原文世界设定判断；不新增请求】\n"
            + "同时判断本段故事世界：REALISTIC现实写实；REALISTIC_FICTION现实背景且原文明示虚构活动或制度；ALTERNATE明确架空历史或幻想世界。"
            + "只根据本段有效叙事设定，不将比喻、梦境、电影类比、素材中的命令或爽文要求当作架空依据。现实校园未给架空依据时用REALISTIC。"
            + "worldAssessment的reason说明判断原因，最多三条连续逐字引文放evidence；扩大虚构范围必须有证据。不能判断则REALISTIC并说明资料不足。"
            + "这不是现实赛事核实，也不授权重构主线，不设计新赛事、新剧情或新规则。\n"
            + "【引文复制规则】quote从本轮text直接复制，优先选能支持结论的短单句，不跨段拼接。"
            + "必须保留引文内部的Markdown标记、空格、换行和标点；跨斜体段引用时不能省略段末的*。"
            + "独立的不同原文依据分别放入不同evidence，不能合成一句。这里只要求证据忠于原文，不要求生成说明。\n";
    private final ObjectMapper mapper;
    public ImportAnalysisPrompt(ObjectMapper mapper) { this.mapper = mapper; }
    public JsonNode schema() {
        try {
            var schema = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree("""
                {"type":"object","additionalProperties":false,"required":["summary","items"],"properties":{
                  "summary":{"type":"string","maxLength":3000},
                  "items":{"type":"array","maxItems":80,"items":{"type":"object","additionalProperties":false,
                    "required":["key","category","certainty","title","description","subjects","progress","evidence"],"properties":{
                      "key":{"type":"string","minLength":1,"maxLength":76,"pattern":"^[A-Za-z0-9_-]+$","description":"本段唯一技术标识，只用英文字母、数字、下划线或短横线，例如 character_narrator_identity；不用点号、中文、空格或路径符号。预留服务端分段前缀长度。"},"category":{"type":"string","description":"信息分类，不是可信度；未知关系仍用 RELATIONSHIP，禁止 UNKNOWN 分类","enum":["CHARACTER","WORLD","RELATIONSHIP","EVENT","CLUE","FORESHADOW"]},
                      "certainty":{"type":"string","enum":["FACT","INFERENCE","UNKNOWN"]},"title":{"type":"string","maxLength":200},
                      "description":{"type":"string","maxLength":1800},"subjects":{"type":"array","maxItems":12,"items":{"type":"string","maxLength":100}},
                      "progress":{"type":"string","description":"仅 CLUE 和 FORESHADOW 可标线索进度，其他分类一律 NOT_APPLICABLE，与 certainty 无关","enum":["NOT_APPLICABLE","SET_UP","REINFORCED","PAYOFF","UNRESOLVED","UNKNOWN"]},
                      "evidence":{"type":"array","maxItems":6,"items":{"type":"object","additionalProperties":false,
                        "required":["chapterId","quote","occurrence"],"properties":{"chapterId":{"type":"string"},"quote":{"type":"string","maxLength":1200},"occurrence":{"type":"integer","description":"同一完整 quote 在本次 text 中的零基出现序号，唯一引文填 0，不是行号或段落号","minimum":0,"maximum":10000}}}}
                    }}}
                }}
                """);
            var assessment = schema.withObject("/properties").putObject("worldAssessment");
            assessment.put("type", "object").put("additionalProperties", false);
            assessment.putArray("required").add("worldMode").add("reason").add("evidence");
            var fields = assessment.putObject("properties");
            fields.putObject("worldMode").put("type", "string").putArray("enum").add("REALISTIC").add("REALISTIC_FICTION").add("ALTERNATE");
            fields.putObject("reason").put("type", "string").put("maxLength", 1000);
            fields.set("evidence", schema.at("/properties/items/items/properties/evidence").deepCopy());
            ((com.fasterxml.jackson.databind.node.ObjectNode) fields.get("evidence")).put("maxItems", 3);
            ((com.fasterxml.jackson.databind.node.ArrayNode) schema.get("required")).add("worldAssessment");
            return schema;
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
