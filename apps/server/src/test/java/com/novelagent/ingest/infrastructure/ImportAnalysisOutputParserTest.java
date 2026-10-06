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
    @Test void promptSeparatesCategoriesCertaintyAndQuoteOrdinals() {
        assertThat(ImportAnalysisPrompt.SYSTEM).contains("category 绝不能写 UNKNOWN", "不是段落编号", "必须是 0");
    }
}
