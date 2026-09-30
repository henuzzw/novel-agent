package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class StoryBibleOutputSchema {
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,
             "required":["content","changeSummary"],
             "properties":{
              "content":{"type":"object","additionalProperties":false,
               "required":["logline","theme","worldSetting","worldRules","protagonist","protagonistArc","supportingCharacters","relationshipDynamics","centralConflict","stakes","narrativeStyle","endingDirection","hardConstraints","openQuestions"],
               "properties":{
               "logline":{"type":"string"},"theme":{"type":"string"},"worldSetting":{"type":"string"},
               "worldRules":{"type":"array","items":{"type":"string"}},
               "protagonist":{"type":"string"},"protagonistArc":{"type":"string"},
               "supportingCharacters":{"type":"array","items":{"type":"string"}},
               "relationshipDynamics":{"type":"array","items":{"type":"string"}},
               "centralConflict":{"type":"string"},"stakes":{"type":"string"},
               "narrativeStyle":{"type":"string"},"endingDirection":{"type":"string"},
               "hardConstraints":{"type":"array","items":{"type":"string"}},
               "openQuestions":{"type":"array","items":{"type":"string"}}
             }},
             "changeSummary":{"type":"array","items":{"type":"string"}}
             }}
            """;
    private final JsonNode value;

    public StoryBibleOutputSchema(ObjectMapper objectMapper) {
        try { this.value = objectMapper.readTree(SCHEMA); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("故事圣经输出 Schema 配置无效", exception); }
    }

    public JsonNode value() { return value.deepCopy(); }
}
