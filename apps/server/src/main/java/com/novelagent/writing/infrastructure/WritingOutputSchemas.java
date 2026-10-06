package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.writing.domain.WritingStyleProfile;
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

    JsonNode stylePreview() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        string(properties, "title");
        string(properties, "body");
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

    JsonNode contractReview() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        string(properties, "summary");
        ObjectNode issue = objectSchema();
        ObjectNode fields = issue.putObject("properties");
        string(fields, "id");
        enumString(fields, "severity", "BLOCKING", "WARNING", "INFO");
        string(fields, "category");
        string(fields, "description");
        string(fields, "evidence");
        string(fields, "suggestion");
        fields.putObject("resolved").put("type", "boolean");
        required(issue, fields);
        properties.putObject("issues").put("type", "array").set("items", issue);
        required(root, properties);
        return root;
    }

    JsonNode qualityReview() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        string(properties, "summary");
        ObjectNode score = objectSchema();
        ObjectNode scoreFields = score.putObject("properties");
        enumString(scoreFields, "dimension", "STYLE", "FLUENCY", "LOGIC", "SCENE");
        nullableInteger(scoreFields, "score");
        scoreFields.withObject("score").put("minimum", 0).put("maximum", 100);
        string(scoreFields, "rationale");
        required(score, scoreFields);
        properties.putObject("scores").put("type", "array").set("items", score);
        ObjectNode issue = objectSchema();
        ObjectNode fields = issue.putObject("properties");
        string(fields, "id");
        enumString(fields, "severity", "WARNING", "INFO");
        enumString(fields, "category", "STYLE", "FLUENCY", "LOGIC", "SCENE");
        string(fields, "description");
        string(fields, "evidence");
        string(fields, "suggestion");
        fields.putObject("resolved").put("type", "boolean");
        required(issue, fields);
        properties.putObject("issues").put("type", "array").set("items", issue);
        required(root, properties);
        return root;
    }

    JsonNode styleRecommendation(java.util.List<WritingStyleProfile> presets) {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        string(properties, "summary");
        ObjectNode recommendation = objectSchema();
        ObjectNode fields = recommendation.putObject("properties");
        enumString(fields, "presetName", presets.stream().map(profile -> profile.name()).toArray(String[]::new));
        string(fields, "reason");
        string(fields, "tradeoff");
        ObjectNode evidence = objectSchema();
        ObjectNode evidenceFields = evidence.putObject("properties");
        enumString(evidenceFields, "field", "logline", "theme", "worldSetting", "protagonist", "protagonistArc",
                "centralConflict", "stakes", "narrativeStyle", "endingDirection");
        string(evidenceFields, "quote");
        required(evidence, evidenceFields);
        fields.putObject("evidence").put("type", "array").set("items", evidence);
        required(recommendation, fields);
        properties.putObject("recommendations").put("type", "array").set("items", recommendation);
        required(root, properties);
        return root;
    }

    JsonNode writingStyle() {
        ObjectNode root = objectSchema();
        ObjectNode properties = root.putObject("properties");
        for (String field : new String[] {"name", "narrativeVoice", "sentenceRhythm", "descriptionFocus",
                "dialogueStyle", "emotionalExpression", "pacing"}) string(properties, field);
        strings(properties, "avoidPatterns");
        nullableString(properties, "basePresetId");
        nullableInteger(properties, "basePresetVersion");
        ObjectNode craft = objectSchema();
        ObjectNode fields = craft.putObject("properties");
        for (String field : new String[] {"narratorPosition", "paragraphMoves", "sentenceMoves", "wordChoice",
                "dialogueMoves", "rhetoricMoves", "sceneVariants", "revisionChecks"}) {
            string(fields, field);
            fields.withObject(field).put("maxLength", 1000);
        }
        ObjectNode example = objectSchema();
        ObjectNode exampleFields = example.putObject("properties");
        for (String field : new String[] {"scene", "facts", "positive", "nearMiss", "explanation"}) string(exampleFields, field);
        required(example, exampleFields);
        fields.putObject("examples").put("type", "array").put("maxItems", 0).set("items", example);
        ObjectNode evidence = objectSchema();
        ObjectNode evidenceFields = evidence.putObject("properties");
        enumString(evidenceFields, "dimension", "narratorPosition", "paragraphMoves", "sentenceMoves", "wordChoice",
                "dialogueMoves", "rhetoricMoves", "sceneVariants", "revisionChecks");
        string(evidenceFields, "quote");
        evidenceFields.withObject("quote").put("maxLength", 300);
        string(evidenceFields, "explanation");
        evidenceFields.withObject("explanation").put("maxLength", 600);
        required(evidence, evidenceFields);
        fields.putObject("evidence").put("type", "array").put("minItems", 1).put("maxItems", 6).set("items", evidence);
        required(craft, fields);
        properties.set("craft", craft);
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
