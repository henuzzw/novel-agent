package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class JsonSchemaOutputProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private JsonSchemaOutputProtocol protocol() throws Exception {
        return new JsonSchemaOutputProtocol(json.readTree("""
                {"type":"object","additionalProperties":false,"required":["action","quotes","content"],"properties":{
                "action":{"type":"string","enum":["NO_CHANGE","REVISED"]},
                "quotes":{"type":"array","minItems":1,"maxItems":2,"items":{"type":"string"}},
                "content":{"type":["object","null"],"additionalProperties":false,"required":["body"],
                "properties":{"body":{"type":"string"}}}}}
                """));
    }
    @Test void exactTextAndNullArePreservedAndSchemaCannotBeMutatedByCaller() throws Exception {
        var protocol = protocol();
        String input = "{\"action\":\"REVISED\",\"quotes\":[\"她说：\\\"好\\\"。\"],\"content\":{\"body\":\"甲\\n\\n乙\"}}";
        assertThat(json.readTree(protocol.decode(input)).at("/content/body").asText()).isEqualTo("甲\n\n乙");
        ((ObjectNode) protocol.wireSchema()).remove("required");
        assertThat(protocol.wireSchema().has("required")).isTrue();
        assertThat(protocol.decode("{\"action\":\"NO_CHANGE\",\"quotes\":[\"原文\"],\"content\":null}")).contains("\"content\":null");
    }
    @Test void rejectsMalformedDuplicateTrailingMissingEnumWrongTypeAndArrayBoundsBeforeSaving() throws Exception {
        var protocol = protocol();
        for (String input : List.of("```json\n{}\n```", "{} {}",
                "{\"action\":\"NO_CHANGE\",\"action\":\"REVISED\"}", "{}",
                "{\"action\":\"WRONG\",\"quotes\":[\"a\"],\"content\":null}",
                "{\"action\":\"NO_CHANGE\",\"quotes\":\"a\",\"content\":null}",
                "{\"action\":\"NO_CHANGE\",\"quotes\":[],\"content\":null}",
                "{\"action\":\"NO_CHANGE\",\"quotes\":[\"a\",\"b\",\"c\"],\"content\":null}",
                "{\"action\":\"NO_CHANGE\",\"quotes\":[\"a\"],\"content\":{},\"publish\":true}"))
            assertThatThrownBy(() -> protocol.decode(input)).hasMessageStartingWith("JSON响应输出校验失败");
    }
    @Test void unsupportedSchemaIsRejectedInsteadOfSilentlyClaimingValidation() throws Exception {
        var schema = json.readTree("{\"type\":\"object\",\"oneOf\":[]}");
        assertThatThrownBy(() -> new JsonSchemaOutputProtocol(schema)).hasMessageContaining("不支持");
    }
}
