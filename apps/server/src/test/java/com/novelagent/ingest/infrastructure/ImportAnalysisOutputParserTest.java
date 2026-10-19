package com.novelagent.ingest.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.ingest.domain.ImportAnalysis;
import java.util.List;
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

    @Test void clueProgressIsKeptOnlyForCluesWithoutChangingCertainty() {
        for (String category : new String[] {"CHARACTER", "WORLD", "RELATIONSHIP", "EVENT"}) {
            var root = output(); item(root).put("category", category).put("progress", "PAYOFF");
            var parsed = parser.parse(root.toString()).items().getFirst();
            assertThat(parsed.progress()).isEqualTo("NOT_APPLICABLE");
            assertThat(parsed.certainty()).isEqualTo("UNKNOWN");
        }

        var foreshadow = output(); item(foreshadow).put("category", "FORESHADOW").put("progress", "UNRESOLVED");
        assertThat(parser.parse(foreshadow.toString()).items().getFirst().progress()).isEqualTo("UNRESOLVED");
    }
    @Test void rejectsInvalidOutputAtTheExactFieldWithoutEchoingSource() {
        var root = output(); item(root).put("category", "UNKNOWN");
        assertThatThrownBy(() -> parser.parse(root.toString())).hasMessageContaining("items[0].category")
                .hasMessageContaining("UNKNOWN 只能");

        var fact = output(); item(fact).put("certainty", "FACT");
        assertThatThrownBy(() -> parser.parse(fact.toString())).hasMessageContaining("items[0]")
                .hasMessageContaining("事实或推测必须附原文依据").hasMessageNotContaining("关系未知");
        item(fact).put("certainty", "UNKNOWN").put("key", "中文标识");
        assertThatThrownBy(() -> parser.parse(fact.toString())).hasMessageContaining("解析项标识无效");

        assertThatThrownBy(() -> parser.parse("{private manuscript")).hasMessageContaining("合法的 JSON")
                .hasMessageNotContaining("private manuscript");
        assertThatThrownBy(() -> parser.parse(output() + " {}")).hasMessageContaining("合法的 JSON");
        assertThatThrownBy(() -> parser.parse("null")).hasMessageContaining("根节点");
        assertThatThrownBy(() -> parser.parse(null)).hasMessageContaining("响应为空");

        var missing = output(); item(missing).remove("subjects");
        assertThatThrownBy(() -> parser.parse(missing.toString())).hasMessageContaining("subjects 和 evidence");
        var second = output();
        ((ArrayNode) item(second).path("evidence")).addObject()
                .put("chapterId", "private").put("quote", "private manuscript").put("occurrence", "0");
        String raw = second.toString();
        assertThatThrownBy(() -> parser.parse(raw)).hasMessageContaining("items[0].evidence[0]")
                .hasMessageNotContaining("private manuscript");
    }
    @Test void normalizesTechnicalKeysAndRejectsCollisionsOrInvalidFormats() {
        var root = output();
        item(root).put("key", "character.narrator.identity_and_learning");
        item(root).put("certainty", "FACT");
        ((ArrayNode) item(root).path("evidence")).addObject()
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

        for (String second : new String[] { "character.narrator", "character_narrator" }) {
            var duplicate = output();
            item(duplicate).put("key", "character.narrator");
            ((ArrayNode) duplicate.path("items")).add(item(duplicate).deepCopy().put("key", second));
            assertThatThrownBy(() -> parser.parse(duplicate.toString())).hasMessageContaining("items[1].key")
                    .hasMessageContaining("标识重复").hasMessageNotContaining("关系未知");
        }

        for (String key : new String[] { "", "中文标识", "character narrator", "character/narrator", "a".repeat(77) }) {
            var invalid = output(); item(invalid).put("key", key);
            assertThatThrownBy(() -> parser.parse(invalid.toString())).hasMessageContaining("items[0].key")
                    .hasMessageContaining("1至76位").hasMessageNotContaining(key.isEmpty() ? "private manuscript" : key);
        }
        var longest = output(); item(longest).put("key", "a".repeat(76));
        var content = new ImportAnalysis.Content(List.of(), List.of())
                .append(parser.parse(longest.toString()), 39);
        assertThat(content.items().getFirst().key()).hasSize(80).startsWith("b39_");
    }

}
