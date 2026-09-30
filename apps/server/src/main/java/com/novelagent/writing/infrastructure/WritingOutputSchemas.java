package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
class WritingOutputSchemas {
    private final ObjectMapper mapper;

    WritingOutputSchemas(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode contract() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        string(properties, "chapterTitle");
        string(properties, "pov");
        string(properties, "objective");
        string(properties, "storyTime");
        strings(properties, "locations");
        strings(properties, "requiredBeats");
        strings(properties, "requiredReveals");
        strings(properties, "forbiddenFacts");
        string(properties, "expectedExitState");
        strings(properties, "foreshadowActions");
        string(properties, "hook");
        integer(properties, "suggestedMinWords");
        integer(properties, "suggestedMaxWords");
        required(root, properties);
        return root;
    }

    JsonNode manuscript() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        ObjectNode content = objectSchema();
        ObjectNode contentProperties = content.putObject("properties");
        string(contentProperties, "title");
        string(contentProperties, "body");
        string(contentProperties, "summary");
        strings(contentProperties, "continuityNotes");
        required(content, contentProperties);
        properties.set("content", content);
        strings(properties, "changeSummary");
        required(root, properties);
        return root;
    }

    JsonNode review() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        string(properties, "summary");
        ObjectNode issue = objectSchema();
        ObjectNode issueProperties = issue.putObject("properties");
        string(issueProperties, "id");
        enumString(issueProperties, "severity", "BLOCKING", "WARNING", "INFO");
        string(issueProperties, "category");
        string(issueProperties, "description");
        string(issueProperties, "evidence");
        string(issueProperties, "suggestion");
        issueProperties.putObject("resolved").put("type", "boolean");
        required(issue, issueProperties);
        properties.putObject("issues").put("type", "array").set("items", issue);
        ObjectNode fact = objectSchema();
        ObjectNode factProperties = fact.putObject("properties");
        string(factProperties, "id");
        enumString(factProperties, "factType", "ENTITY_UPSERT", "EVENT_CREATE", "STATE_CHANGE",
                "RELATION_CHANGE", "KNOWLEDGE_CHANGE", "FORESHADOW_CHANGE");
        string(factProperties, "subject");
        string(factProperties, "predicate");
        string(factProperties, "object");
        string(factProperties, "evidence");
        number(factProperties, "confidence");
        factProperties.set("payload", factPayload());
        enumString(factProperties, "decision", "PENDING", "ACCEPTED", "REJECTED");
        required(fact, factProperties);
        properties.putObject("factProposals").put("type", "array").set("items", fact);
        required(root, properties);
        return root;
    }

    private ObjectNode objectSchema() {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "object");
        node.put("additionalProperties", false);
        return node;
    }

    private void string(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "string");
    }

    private void integer(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "integer");
    }

    private void number(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "number").put("minimum", 0).put("maximum", 1);
    }

    private void strings(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "array").putObject("items").put("type", "string");
    }

    private void enumString(ObjectNode properties, String name, String... values) {
        ObjectNode node = properties.putObject(name);
        node.put("type", "string");
        ArrayNode choices = node.putArray("enum");
        for (String value : values) {
            choices.add(value);
        }
    }

    private void required(ObjectNode root, ObjectNode properties) {
        ArrayNode required = root.putArray("required");
        properties.fieldNames().forEachRemaining(required::add);
    }

    private ObjectNode factPayload() {
        ObjectNode schema = objectSchema();
        ObjectNode properties = schema.putObject("properties");
        nullableEnumString(properties, "entityType", "CHARACTER", "LOCATION", "ORGANIZATION", "ITEM", "SECRET", "RULE");
        nullableString(properties, "entityName");
        nullableString(properties, "eventTitle");
        nullableString(properties, "eventSummary");
        nullableString(properties, "storyTime");
        nullableStrings(properties, "participants");
        nullableEnumString(properties, "fieldKey", "character.location", "character.physical_state",
                "character.emotional_state", "character.current_goal", "character.alive_status",
                "item.location", "item.holder", "item.condition");
        nullableString(properties, "beforeValue");
        nullableString(properties, "afterValue");
        nullableString(properties, "sourceEntityName");
        nullableString(properties, "targetEntityName");
        nullableString(properties, "relationType");
        nullableString(properties, "characterName");
        nullableEnumString(properties, "knowledgeType", "WITNESSED", "LEARNED", "BELIEVED", "SUSPECTED");
        nullableEnumString(properties, "beliefTruth", "TRUE", "FALSE", "UNVERIFIED");
        nullableString(properties, "statement");
        nullableString(properties, "foreshadowTitle");
        nullableString(properties, "targetEffect");
        nullableEnumString(properties, "foreshadowStatus", "PLANNED", "PLANTED", "REINFORCED",
                "PARTIALLY_REVEALED", "RESOLVED", "ABANDONED");
        nullableInteger(properties, "plannedResolveChapter");
        nullableString(properties, "entityId");
        nullableString(properties, "sourceEntityId");
        nullableString(properties, "targetEntityId");
        nullableString(properties, "characterId");
        nullableEnumString(properties, "stateEntityType", "CHARACTER", "ITEM");
        required(schema, properties);
        return schema;
    }

    private void nullableString(ObjectNode properties, String name) {
        ArrayNode types = properties.putObject(name).putArray("type");
        types.add("string");
        types.add("null");
    }

    private void nullableInteger(ObjectNode properties, String name) {
        ArrayNode types = properties.putObject(name).putArray("type");
        types.add("integer");
        types.add("null");
    }

    private void nullableEnumString(ObjectNode properties, String name, String... values) {
        ObjectNode node = properties.putObject(name);
        ArrayNode types = node.putArray("type");
        types.add("string");
        types.add("null");
        ArrayNode choices = node.putArray("enum");
        for (String value : values) {
            choices.add(value);
        }
        choices.addNull();
    }

    private void nullableStrings(ObjectNode properties, String name) {
        ObjectNode node = properties.putObject(name);
        ArrayNode types = node.putArray("type");
        types.add("array");
        types.add("null");
        node.putObject("items").put("type", "string");
    }
}
