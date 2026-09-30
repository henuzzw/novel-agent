package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class OutlineOutputSchema {
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["content","changeSummary"],
             "properties":{
              "content":{"type":"object","additionalProperties":false,
               "required":["title","premise","structureSummary","pacingStrategy","suggestedMinWords","suggestedMaxWords","arcs"],
               "properties":{
              "title":{"type":"string"},"premise":{"type":"string"},"structureSummary":{"type":"string"},
              "pacingStrategy":{"type":"string"},"suggestedMinWords":{"type":"integer"},"suggestedMaxWords":{"type":"integer"},
              "arcs":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,
               "required":["ordinal","title","objective","mainConflict","turningPoint","outcome","suggestedMinWords","suggestedMaxWords","chapters"],
               "properties":{"ordinal":{"type":"integer"},"title":{"type":"string"},"objective":{"type":"string"},
                "mainConflict":{"type":"string"},"turningPoint":{"type":"string"},"outcome":{"type":"string"},
                "suggestedMinWords":{"type":"integer"},"suggestedMaxWords":{"type":"integer"},
                "chapters":{"type":"array","minItems":1,"items":{"type":"object","additionalProperties":false,
                 "required":["number","title","pov","objective","coreEvent","reveal","endingHook","suggestedMinWords","suggestedMaxWords","status"],
                 "properties":{"number":{"type":"integer"},"title":{"type":"string"},"pov":{"type":"string"},
                  "objective":{"type":"string"},"coreEvent":{"type":"string"},"reveal":{"type":"string"},
                  "endingHook":{"type":"string"},"suggestedMinWords":{"type":"integer"},"suggestedMaxWords":{"type":"integer"},
                  "status":{"type":"string","enum":["OCCURRED","PLANNED"]}}}}
               }}}
             }},
             "changeSummary":{"type":"array","items":{"type":"string"}}
             }}
            """;
    private final JsonNode value;
    public OutlineOutputSchema(ObjectMapper mapper) {
        try { value = mapper.readTree(SCHEMA); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("分层大纲输出 Schema 配置无效", exception); }
    }
    public JsonNode value() { return value.deepCopy(); }
}
