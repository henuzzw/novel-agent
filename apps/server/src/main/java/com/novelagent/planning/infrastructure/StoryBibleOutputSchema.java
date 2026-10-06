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
               "required":["logline","theme","worldSetting","worldRules","protagonist","protagonistArc","supportingCharacters","relationshipDynamics","centralConflict","stakes","narrativeStyle","endingDirection","hardConstraints","openQuestions","characterBlueprints"],
               "properties":{
               "logline":{"type":"string"},"theme":{"type":"string"},"worldSetting":{"type":"string"},
               "worldRules":{"type":"array","items":{"type":"string"}},
               "protagonist":{"type":"string"},"protagonistArc":{"type":"string"},
               "supportingCharacters":{"type":"array","items":{"type":"string"}},
               "relationshipDynamics":{"type":"array","items":{"type":"string"}},
               "centralConflict":{"type":"string"},"stakes":{"type":"string"},
               "narrativeStyle":{"type":"string"},"endingDirection":{"type":"string"},
               "hardConstraints":{"type":"array","items":{"type":"string"}},
               "openQuestions":{"type":"array","items":{"type":"string"}},
               "characterBlueprints":{"type":"array","minItems":1,"maxItems":12,"items":{
                 "type":"object","additionalProperties":false,
                 "required":["name","role","identity","appearance","background","externalPersonality","internalPersonality","coreDesire","fear","flaw","values","speechStyle","behaviorHabits","abilitiesAndLimits","behaviorBoundaries","secret","characterArc","openingState","initialRelationships","initialPossessions","knowledgeBoundaries","gender","ageDescription"],
                 "properties":{
                   "name":{"type":"string","minLength":1,"maxLength":100},
                   "role":{"type":"string","enum":["PROTAGONIST","SUPPORTING","MINOR"]},
                   "gender":{"type":"string","maxLength":40},
                   "ageDescription":{"type":"string","maxLength":100},
                   "identity":{"type":"string","minLength":1,"maxLength":3000},
                   "appearance":{"type":"string","maxLength":3000},
                   "background":{"type":"string","maxLength":3000},
                   "externalPersonality":{"type":"string","maxLength":3000},
                   "internalPersonality":{"type":"string","maxLength":3000},
                   "coreDesire":{"type":"string","minLength":1,"maxLength":3000},
                   "fear":{"type":"string","maxLength":3000},
                   "flaw":{"type":"string","maxLength":3000},
                   "values":{"type":"string","maxLength":3000},
                   "speechStyle":{"type":"string","maxLength":3000},
                   "behaviorHabits":{"type":"string","maxLength":3000},
                   "abilitiesAndLimits":{"type":"string","maxLength":3000},
                   "behaviorBoundaries":{"type":"string","maxLength":3000},
                   "secret":{"type":"string","maxLength":3000},
                   "characterArc":{"type":"string","maxLength":3000},
                   "openingState":{"type":"string","maxLength":3000},
                   "initialRelationships":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":1000}},
                   "initialPossessions":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":1000}},
                   "knowledgeBoundaries":{"type":"array","maxItems":20,"items":{"type":"string","minLength":1,"maxLength":1000}}
                 }
               }}
             }},
             "changeSummary":{"type":"array","items":{"type":"string"}}
             }}
            """;
    private final JsonNode value;

    public StoryBibleOutputSchema(ObjectMapper objectMapper) {
        try {
            this.value = objectMapper.readTree(SCHEMA);
            ReaderExperienceSeedSchema.add((com.fasterxml.jackson.databind.node.ObjectNode) value, objectMapper);
        }
        catch (JsonProcessingException exception) { throw new IllegalStateException("故事圣经输出 Schema 配置无效", exception); }
    }

    public JsonNode value() { return value.deepCopy(); }
}
