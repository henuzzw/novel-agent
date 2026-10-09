package com.novelagent.ingest.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class ImportAnalysisOutputParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ImportAnalysisOutputParser parser = new ImportAnalysisOutputParser(mapper);

    private ObjectNode output() {
        var root = mapper.createObjectNode().put("summary", "只分析本段");
        var item = root.putArray("items").addObject().put("key", "a").put("category", "RELATIONSHIP")
                .put("certainty", "UNKNOWN").put("title", "关系未知").put("description", "原文没有说明关系")
                .put("progress", "UNKNOWN");
        item.putArray("subjects").add("甲"); item.putArray("evidence");
        return root;
    }
    private ObjectNode item(ObjectNode root) { return (ObjectNode) root.path("items").get(0); }

    @Test void clearsNonClueProgressWithoutChangingCertainty() {
        for (String category : new String[] {"CHARACTER", "WORLD", "RELATIONSHIP", "EVENT"}) {
            var root = output(); item(root).put("category", category).put("progress", "PAYOFF");
            var parsed = parser.parse(root.toString()).items().getFirst();
            assertThat(parsed.progress()).isEqualTo("NOT_APPLICABLE");
            assertThat(parsed.certainty()).isEqualTo("UNKNOWN");
        }
    }
    @Test void preservesActualClueProgress() {
        var root = output(); item(root).put("category", "FORESHADOW").put("progress", "UNRESOLVED");
        assertThat(parser.parse(root.toString()).items().getFirst().progress()).isEqualTo("UNRESOLVED");
    }
    @Test void unknownIsNotAValidCategoryAndErrorIdentifiesField() {
        var root = output(); item(root).put("category", "UNKNOWN");
        assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("items[0].category")
                .hasMessageContaining("UNKNOWN 只能");
    }
    @Test void preservesDomainFailureReasonsWithoutPrintingSourceText() {
        var root = output(); item(root).put("certainty", "FACT");
        assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("items[0]")
                .hasMessageContaining("事实或推测必须附原文依据").hasMessageNotContaining("关系未知");
        item(root).put("certainty", "UNKNOWN").put("key", "中文标识");
        assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("解析项标识无效");
    }
    @Test void rejectsMalformedJsonAndTrailingDataWithoutEchoingResponse() {
        assertThatThrownBy(() -> parser.parse("{private manuscript")).hasMessageContaining("合法的 JSON")
                .hasMessageNotContaining("private manuscript");
        assertThatThrownBy(() -> parser.parse(output() + " {}")).hasMessageContaining("合法的 JSON");
        assertThatThrownBy(() -> parser.parse("null")).hasMessageContaining("根节点");
        assertThatThrownBy(() -> parser.parse(null)).hasMessageContaining("响应为空");
    }
    @Test void rejectsMissingArraysAndCoercedEvidencePositions() {
        var root = output(); item(root).remove("subjects");
        assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("subjects 和 evidence");
        var second = output();
        ((com.fasterxml.jackson.databind.node.ArrayNode) item(second).path("evidence")).addObject()
                .put("chapterId", "private").put("quote", "private manuscript").put("occurrence", "0");
        String raw = second.toString();
        assertThatThrownBy(() -> parser.parse(raw)).hasMessageContaining("items[0].evidence[0]")
                .hasMessageNotContaining("private manuscript");
    }
    @Test void normalizesDottedTechnicalKeysWithoutChangingStoryFieldsOrEvidence() {
        var root = output();
        item(root).put("key", "character.narrator.identity_and_learning");
        item(root).put("certainty", "FACT");
        ((com.fasterxml.jackson.databind.node.ArrayNode) item(root).path("evidence")).addObject()
                .put("chapterId", "fcd21055-2ab9-4305-a674-5572a4e5302b")
                .put("quote", "他写下 character.narrator。").put("occurrence", 0);
        var parsed = parser.parse(root.toString()).items().getFirst();
        assertThat(parsed.key()).isEqualTo("character_narrator_identity_and_learning");
        assertThat(parsed.title()).isEqualTo(item(root).path("title").asText());
        assertThat(parsed.description()).isEqualTo(item(root).path("description").asText());
        assertThat(parsed.subjects()).containsExactly("甲");
        assertThat(parsed.certainty()).isEqualTo("FACT");
        assertThat(parsed.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.quote()).isEqualTo("他写下 character.narrator。");
            assertThat(evidence.occurrence()).isZero();
            assertThat(evidence.chapterId().toString()).isEqualTo("fcd21055-2ab9-4305-a674-5572a4e5302b");
        });
        assertThat(parser.parse(root.toString())).isEqualTo(parser.parse(root.toString()));
    }

    @Test void normalizationCollisionsAndRepeatedKeysFailAtTheExactFieldInsteadOfDroppingItems() {
        for (String second : new String[] { "character.narrator", "character_narrator" }) {
            var root = output();
            item(root).put("key", "character.narrator");
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("items")).add(item(root).deepCopy().put("key", second));
            assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("items[1].key")
                    .hasMessageContaining("标识重复").hasMessageNotContaining("关系未知");
        }
    }

    @Test void rejectsInvalidKeyFormatsAndLeavesRoomForThePersistedSegmentPrefix() {
        for (String key : new String[] { "", "中文标识", "character narrator", "character/narrator", "a".repeat(77) }) {
            var root = output(); item(root).put("key", key);
            assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("items[0].key")
                    .hasMessageContaining("1至76位").hasMessageNotContaining(key.isEmpty() ? "private manuscript" : key);
        }
        var root = output(); item(root).put("key", "a".repeat(76));
        var content = new com.novelagent.ingest.domain.ImportAnalysis.Content(java.util.List.of(), java.util.List.of())
                .append(parser.parse(root.toString()), 39);
        assertThat(content.items().getFirst().key()).hasSize(80).startsWith("b39_");
    }

    @Test void keySchemaAndPromptDescribeTheSameSafeIdentifierContract() {
        var schema = new ImportAnalysisPrompt(mapper).schema().path("properties").path("items").path("items")
                .path("properties").path("key");
        assertThat(schema.path("minLength").asInt()).isEqualTo(1);
        assertThat(schema.path("maxLength").asInt()).isEqualTo(76);
        assertThat(schema.path("pattern").asText()).isEqualTo("^[A-Za-z0-9_-]+$");
        assertThat(ImportAnalysisPrompt.SYSTEM).contains("1至76位", "不用点号", "不重复", "中文名称放title或subjects");
    }
}
