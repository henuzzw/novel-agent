package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class ReaderExperienceSeedSchema {
    private ReaderExperienceSeedSchema() { }
    static void add(ObjectNode schema, ObjectMapper mapper) throws com.fasterxml.jackson.core.JsonProcessingException {
        var content = (ObjectNode) schema.path("properties").path("content");
        ((ArrayNode) content.path("required")).add("readerExperiencePlans");
        ((ObjectNode) content.path("properties")).set("readerExperiencePlans", mapper.readTree("""
                {"type":"array","maxItems":80,"items":{"type":"object","additionalProperties":false,
                 "required":["key","kind","title","promise","setup","payoff","aftermath","plannedChapter"],
                 "properties":{"key":{"type":"string","minLength":1,"maxLength":80,"pattern":"^[a-zA-Z0-9_-]+$"},
                 "kind":{"type":"string","enum":["PROMISE","FORESHADOW"]},
                 "title":{"type":"string","minLength":1,"maxLength":200},
                 "promise":{"type":"string","minLength":1,"maxLength":4000},
                 "setup":{"type":"string","maxLength":4000},"payoff":{"type":"string","maxLength":4000},
                 "aftermath":{"type":"string","maxLength":4000},"plannedChapter":{"type":["integer","null"],"minimum":1}}}}
                """));
    }
}
