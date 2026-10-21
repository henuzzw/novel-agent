package com.novelagent.planning.infrastructure;

import static com.novelagent.platform.support.Text.isBlank;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.ModelOutputAvailability;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Validates the small B/C transport contract, not literary quality or evidence truth. */
final class JsonSchemaOutputProtocol implements ModelOutputProtocol {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JsonNode schema;

    JsonSchemaOutputProtocol(JsonNode schema) {
        if (schema == null || !schema.isObject()) throw invalid("$", "缺少返回结构");
        this.schema = schema.deepCopy();
        requireSupported(this.schema, "$", 0);
    }

    @Override public JsonNode wireSchema() { return schema.deepCopy(); }
    @Override public String snapshotName(String name) { return name; }
    @Override public String instructions() {
        return "\n【本次原生 JSON Schema 返回协议；优先于模板旧格式说明】\n"
                + "仅返回符合服务端 outputSchema/text.format 的一个 JSON 对象，不输出 Markdown 标题、代码围栏或对象外说明。"
                + "必须包含所有规定字段，不适用的字符串填空字符串、数组填[]、可空正文填null。"
                + "引文分别放入evidence数组的quote，只含连续逐字原文；新正文完整放入content.body，不拆成文学属性。"
                + "结构约束不授权新增事实、扩大修订权限或自动发布。";
    }

    @Override public String preparePrompt(String prompt) {
        String text = prompt == null ? "" : prompt;
        // Remove only the known transport footer, leaving stage duties and saved creative guidance intact.
        text = text.replaceAll("(?s)【纯文本返回】.*?(?=\\n\\s*【|\\z)", "")
                .replace("仅执行当前工作流职责，返回纯文本，按本次保存标题组织资料，不输出JSON或Schema，不扩大操作范围。",
                        "仅执行当前工作流职责，遵循本轮服务端返回协议，不扩大操作范围。")
                .replace("仅返回当前阶段约定的纯文本，不使用JSON或JSON Schema，不自行发布规划或正文。",
                        "仅返回当前阶段约定的结果，不自行发布规划或正文。");
        return text.stripTrailing();
    }

    @Override public String decode(String raw) {
        if (isBlank(raw)) throw invalid("$", "未返回结果");
        ModelOutputAvailability.requireAvailable(raw);
        JsonNode output;
        try { output = JSON.readTree(raw); }
        catch (IOException error) { throw invalid("$", "不是单个合法JSON对象"); }
        validate(schema, output, "$", 0);
        return output.toString();
    }

    private static void requireSupported(JsonNode schema, String path, int depth) {
        if (depth > 32) throw invalid(path, "协议嵌套过深");
        schema.fieldNames().forEachRemaining(key -> {
            if (!Set.of("type", "properties", "required", "additionalProperties", "items", "enum",
                    "minItems", "maxItems", "description").contains(key))
                throw invalid(path, "不支持的协议关键字 " + key);
        });
        JsonNode type = schema.path("type");
        if (type.isArray()) type.forEach(value -> requireType(value.asText(), path));
        else requireType(type.asText(), path);
        if (permits(schema, "object")) {
            if (!schema.path("properties").isObject() || !schema.path("required").isArray()
                    || !schema.path("additionalProperties").isBoolean() || schema.path("additionalProperties").asBoolean())
                throw invalid(path, "对象必须明确字段、必填项并禁止额外字段");
            var required = new HashSet<String>();
            schema.path("required").forEach(value -> required.add(value.asText()));
            var fields = new HashSet<String>();
            schema.path("properties").fields().forEachRemaining(field -> {
                fields.add(field.getKey()); requireSupported(field.getValue(), path + "." + field.getKey(), depth + 1);
            });
            if (!fields.equals(required)) throw invalid(path, "原生协议的所有字段必须必填");
        }
        if (permits(schema, "array")) requireSupported(schema.path("items"), path + "[]", depth + 1);
    }

    private static void requireType(String type, String path) {
        if (!Set.of("object", "array", "string", "null").contains(type)) throw invalid(path, "不支持的字段类型");
    }

    private static boolean permits(JsonNode schema, String type) {
        JsonNode types = schema.path("type");
        if (!types.isArray()) return type.equals(types.asText());
        for (var value : types) if (type.equals(value.asText())) return true;
        return false;
    }

    private static void validate(JsonNode schema, JsonNode value, String path, int depth) {
        if (depth > 32 || value == null) throw invalid(path, "缺少字段");
        String type = value.isNull() ? "null" : value.isObject() ? "object" : value.isArray() ? "array"
                : value.isTextual() ? "string" : "unsupported";
        if (!permits(schema, type)) throw invalid(path, "字段类型不符合协议");
        if (schema.has("enum")) {
            boolean found = false;
            for (var option : schema.path("enum")) if (option.equals(value)) found = true;
            if (!found) throw invalid(path, "枚举值不符合协议");
        }
        if (value.isObject()) {
            for (var name : schema.path("required")) if (!value.has(name.asText()))
                throw invalid(path + "." + name.asText(), "缺少必填字段");
            value.fields().forEachRemaining(field -> {
                var child = schema.path("properties").get(field.getKey());
                if (child == null) throw invalid(path, "包含未声明字段");
                validate(child, field.getValue(), path + "." + field.getKey(), depth + 1);
            });
        } else if (value.isArray()) {
            if (value.size() < schema.path("minItems").asInt(0)
                    || value.size() > schema.path("maxItems").asInt(Integer.MAX_VALUE)) throw invalid(path, "记录数量超出协议范围");
            for (int i = 0; i < value.size(); i++) validate(schema.path("items"), value.get(i), path + "[" + i + "]", depth + 1);
        }
    }

    private static IllegalArgumentException invalid(String path, String reason) {
        return new IllegalArgumentException("JSON响应输出校验失败：" + path + "：" + reason);
    }
}
