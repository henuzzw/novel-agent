package com.novelagent.ingest.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ImportAnalysisPrompt {
    public static final String SYSTEM = com.novelagent.prompt.application.AgentPromptDefaults.system("IMPORT_SOURCE_ANALYSIS");
    private final ObjectMapper mapper;
    public ImportAnalysisPrompt(ObjectMapper mapper) { this.mapper = mapper; }
    public JsonNode schema() {
        try { return mapper.readTree("""
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
                """); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
