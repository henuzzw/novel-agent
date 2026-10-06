package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class CreationPreparationSchema {
    private final ObjectMapper mapper;
    private final StoryBibleOutputSchema bibles;
    private final OutlineOutputSchema outlines;
    public CreationPreparationSchema(ObjectMapper mapper, StoryBibleOutputSchema bibles, OutlineOutputSchema outlines) {
        this.mapper = mapper; this.bibles = bibles; this.outlines = outlines;
    }
    public JsonNode world() {
        return object(Map.of("characters", bibles.value().at("/properties/content/properties/characterBlueprints"),
                "entities", array(object(fields("key", "type", "name", "description", "initialState", "owner")), 40)));
    }
    public JsonNode plot() {
        var unit = fields("key", "title", "objective", "conflict", "turningPoint", "endCondition");
        unit.put("startChapter", integer()); unit.put("endChapter", integer());
        unit.put("characters", array(string(), 12)); unit.put("planKeys", array(string(), 80));
        var relation = fields("source", "target", "type", "description"); relation.put("fromChapter", integer());
        var knowledge = fields("character", "information", "source"); knowledge.put("knownFromChapter", integer());
        var timeline = fields("key", "storyTime", "event"); timeline.put("chapter", integer());
        timeline.put("participants", array(string(), 12));
        return object(Map.of("units", array(object(unit), 40), "relationships", array(object(relation), 80),
                "knowledge", array(object(knowledge), 80), "timeline", array(object(timeline), 120),
                "readerExperiencePlans", outlines.value().at("/properties/content/properties/readerExperiencePlans")));
    }
    public JsonNode review() {
        var adjustment = fields("objective", "coreEvent", "reveal", "endingHook", "reason"); adjustment.put("chapterNumber", integer());
        return object(Map.of("summary", string(), "issues", array(object(fields("key", "severity", "category",
                "description", "sourceRef", "evidence", "suggestion")), 40), "adjustments", array(object(adjustment), 40),
                "planLinks", array(object(fields("planId", "factId", "state", "evidence")), 80)));
    }
    private Map<String, JsonNode> fields(String... names) {
        var result = new LinkedHashMap<String, JsonNode>();
        for (String name : names) result.put(name, string());
        return result;
    }
    private ObjectNode string() { return mapper.createObjectNode().put("type", "string"); }
    private ObjectNode integer() { return mapper.createObjectNode().put("type", "integer").put("minimum", 1); }
    private ObjectNode array(JsonNode item, int max) {
        var result = mapper.createObjectNode().put("type", "array").put("maxItems", max); result.set("items", item); return result;
    }
    private ObjectNode object(Map<String, JsonNode> fields) {
        var result = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var required = result.putArray("required"); var properties = result.putObject("properties");
        fields.forEach((name, schema) -> { required.add(name); properties.set(name, schema); });
        return result;
    }
}
