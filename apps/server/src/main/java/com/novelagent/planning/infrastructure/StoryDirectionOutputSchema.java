package com.novelagent.planning.infrastructure;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class StoryDirectionOutputSchema {

    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["directions", "questionsForAuthor", "changeSummary"],
              "properties": {
                "directions": {
                  "type": "array",
                  "minItems": 3,
                  "maxItems": 3,
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": ["title", "premise", "centralConflict", "protagonistArc", "structure", "endingDirection", "audienceFit", "strengths", "risks", "distinctiveFeatures"],
                    "properties": {
                      "title": {"type": "string"},
                      "premise": {"type": "string"},
                      "centralConflict": {"type": "string"},
                      "protagonistArc": {"type": "string"},
                      "structure": {"type": "string"},
                      "endingDirection": {"type": "string"},
                      "audienceFit": {"type": "string"},
                      "strengths": {"type": "array", "items": {"type": "string"}},
                      "risks": {"type": "array", "items": {"type": "string"}},
                      "distinctiveFeatures": {"type": "array", "items": {"type": "string"}}
                    }
                  }
                },
                "questionsForAuthor": {"type": "array", "items": {"type": "string"}},
                "changeSummary": {"type": "array", "items": {"type": "string"}}
              }
            }
            """;

    private final JsonNode value;

    public StoryDirectionOutputSchema(ObjectMapper objectMapper) {
		try {
			this.value = objectMapper.readTree(SCHEMA);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("故事方向输出 Schema 配置无效", exception);
		}
    }

    public JsonNode value() {
        return value.deepCopy();
    }
}
