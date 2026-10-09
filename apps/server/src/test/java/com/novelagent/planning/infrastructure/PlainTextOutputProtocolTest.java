package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PlainTextOutputProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private PlainTextOutputProtocol codec(String schema) throws Exception { return new PlainTextOutputProtocol(json.readTree(schema)); }

    @Test void freeProsePreservesParagraphsQuotesAndOrdinaryHeadings() throws Exception {
        var codec = codec("{\"properties\":{\"text\":{\"type\":\"string\"}}}");
        String body = "# 第一章\n\n她说：\"别走。\"\n他停下。";
        assertThat(json.readTree(codec.decode(body)).path("text").asText()).isEqualTo(body);
        assertThat(codec.instructions()).doesNotContain("## @");
    }
    @Test void labelledNovelKeepsBodySeparateFromMetadata() throws Exception {
        var schema = json.readTree("""
                {"type":"object","required":["content"],"properties":{"content":{"type":"object",
                "required":["title","body","summary"],"properties":{"title":{"type":"string"},
                "body":{"type":"string"},"summary":{"type":"string"}}}}}
                """);
        String body = "她说：\"别走。\"\n\n他停下。";
        var result = json.readTree(new PlainTextOutputProtocol(schema).decode("## @content/title\n第一章\n## @content/body\n" + body + "\n## @content/summary\n他留下了。"));
        assertThat(result.at("/content/body").asText()).isEqualTo(body);
        assertThat(result.at("/content/title").asText()).isEqualTo("第一章");
    }
    @Test void emptyReportAndNoChangeNullableManuscriptAreAccepted() throws Exception {
        var codec = codec("""
                {"type":"object","required":["issues","content"],"properties":{
                "issues":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"}}}},
                "content":{"type":["object","null"],"properties":{"body":{"type":"string"}}}}}
                """);
        JsonNode result = json.readTree(codec.decode("## @issues\n无\n## @content\nnull"));
        assertThat(result.path("issues").isEmpty()).isTrue();
        assertThat(result.path("content").isNull()).isTrue();
        assertThat(codec.instructions()).contains("## @content\n");
    }
    @Test void nestedRecordsUseContinuousIndicesAndKeepEvidenceVerbatim() throws Exception {
        var codec = codec("""
                {"type":"object","required":["items"],"properties":{"items":{"type":"array","items":{
                "type":"object","required":["id","quotes"],"properties":{"id":{"type":"string"},"quotes":{
                "type":"array","items":{"type":"object","required":["text","occurrence"],"properties":{
                "text":{"type":"string"},"occurrence":{"type":"integer"}}}}}}}}}
                """);
        var result = json.readTree(codec.decode("## @items/1/id\nrole_1\n## @items/1/quotes/1/text\n她说：别走。\n## @items/1/quotes/1/occurrence\n0"));
        assertThat(result.at("/items/0/quotes/0/text").asText()).isEqualTo("她说：别走。");
        assertThat(result.at("/items/0/quotes/0/occurrence").isNumber()).isTrue();
        assertThatThrownBy(() -> codec.decode("## @items/2/id\nrole_2\n## @items/2/quotes\n无"))
                .hasMessageContaining("无法保存");
    }
    @Test void duplicateMissingUnknownAndInvalidTypedFieldsFailWithDetails() throws Exception {
        var codec = codec("""
                {"type":"object","required":["verdict","round"],"properties":{
                "verdict":{"type":"string","enum":["NO_CHANGE","REVISED"]},"round":{"type":"integer"}}}
                """);
        assertThatThrownBy(() -> codec.decode("## @verdict\nNO_CHANGE")).hasMessageContaining("缺少标题 round");
        assertThatThrownBy(() -> codec.decode("## @verdict\nUNKNOWN\n## @round\n1")).hasMessageContaining("可选值无效");
        assertThatThrownBy(() -> codec.decode("## @verdict\nNO_CHANGE\n## @round\n很多")).hasMessageContaining("数值无效");
        assertThatThrownBy(() -> codec.decode("## @verdict\nNO_CHANGE\n## @round\n1\n## @round\n2")).hasMessageContaining("重复标题");
        assertThatThrownBy(() -> codec.decode("## @verdict\nNO_CHANGE\n## @round\n1\n## @unknown\n数据")).hasMessageContaining("未知");
    }
    @Test void obsoleteTransportRulesAreRemovedButCreativeRulesRemain() {
        assertThat(PlainTextOutputProtocol.withoutLegacyProtocol("保持人物声口。输出按本次JSON Schema。\n【本阶段返回协议：保留现有结构化接口】\n只输出JSON"))
                .contains("保持人物声口", "纯文本").doesNotContain("JSON Schema", "只输出JSON");
    }
}
