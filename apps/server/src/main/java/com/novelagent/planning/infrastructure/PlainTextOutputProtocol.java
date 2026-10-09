package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Models return prose; only the server projects labelled sections into persistence DTOs. */
final class PlainTextOutputProtocol {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HEADING = Pattern.compile("(?m)^## @([A-Za-z0-9_/-]+)[ \\t]*\\r?$");
    private final JsonNode contract;

    PlainTextOutputProtocol(JsonNode contract) { this.contract = contract; }

    /** Remove obsolete transport-only instructions from saved templates, without changing their creative rules. */
    static String withoutLegacyProtocol(String prompt) {
        if (prompt == null) return "";
        int marker = prompt.indexOf("【本阶段返回协议：保留现有结构化接口】");
        String result = marker < 0 ? prompt : prompt.substring(0, marker).stripTrailing();
        return result.replace("只返回本次Schema规定的JSON", "按本次纯文本保存标题返回")
                .replace("只输出符合本次JSON Schema的JSON", "按本次纯文本保存标题返回")
                .replace("严格遵循本次JSON Schema", "遵循本次纯文本保存标题约定")
                .replace("输出按本次JSON Schema", "输出按本次纯文本保存标题组织")
                .replace("输出形式按本次JSON Schema", "输出形式按本次纯文本保存标题组织")
                .replace("输出严格遵循本次JSON Schema", "输出按本次纯文本保存标题组织")
                .replace("按本次JSON Schema输出呈现", "按本次纯文本保存标题呈现")
                .replace("格式按本次JSON Schema", "格式按本次纯文本保存标题组织");
    }

    String instructions() {
        if (freeText()) return "\n【本次输出】直接返回最终创作文本，不使用 JSON、代码块或说明。";
        StringBuilder guide = new StringBuilder("\n【本次纯文本输出约定，取代旧版返回格式指令】\n"
                + "不输出 JSON，不输出 Schema，不使用代码围栏。用下列二级标题标识保存位置，标题下直接写自然文字。"
                + "重复记录将 * 替换为从 1 开始的连续序号；空列表写‘无’，不要虚构资料。"
                + "正文及逐字引文原样写在对应标题下，不转义换行或引号。标题必须独占一行，不能把保存标识写进小说正文。\n");
        describe(contract, "", guide);
        return guide.toString();
    }

    private static void describe(JsonNode node, String path, StringBuilder guide) {
        String type = type(node);
        if (type.equals("object")) {
            if (permitsNull(node)) guide.append("## @").append(path).append("\n（不适用时只写 null；有内容时省略此标题，使用以下子标题）\n");
            node.path("properties").fields().forEachRemaining(field -> describe(field.getValue(),
                    path.isEmpty() ? field.getKey() : path + "/" + field.getKey(), guide));
        } else if (type.equals("array") && type(node.path("items")).equals("object")) {
            guide.append("## @").append(path).append("\n（空列表写无；有记录时省略此空标题，使用以下记录标题）\n");
            describe(node.path("items"), path + "/*", guide);
        } else {
            guide.append("## @").append(path).append('\n');
            if (type.equals("array")) guide.append("每项一行，以 - 开头；空列表写无。\n");
            else if (type.equals("integer") || type.equals("number")) guide.append("只写数值。\n");
            else if (type.equals("boolean")) guide.append("只写 true 或 false。\n");
            else if (node.has("enum")) guide.append("可选值：").append(String.join(" / ",
                    JSON.convertValue(node.get("enum"), String[].class))).append("。\n");
            if (permitsNull(node)) guide.append("未知或不适用时只写 null。\n");
        }
    }

    String decode(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("模型未返回可读文本");
        // Accept internal test fixtures and old locally generated DTOs; neither provider is asked for this format.
        try {
            if (raw.stripLeading().startsWith("{")) {
                JsonNode parsed = JSON.readTree(raw);
                if (parsed != null && parsed.isObject()) return raw;
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        if (freeText()) return JSON.createObjectNode().put("text", raw.strip()).toString();
        Map<String, String> sections = new LinkedHashMap<>();
        var matcher = HEADING.matcher(raw);
        String key = null;
        int start = 0;
        while (matcher.find()) {
            if (key != null) put(sections, key, raw.substring(start, matcher.start()).strip());
            else if (!raw.substring(0, matcher.start()).isBlank()) throw invalid("开头包含未标识文本");
            key = matcher.group(1); start = matcher.end();
        }
        if (key == null) throw invalid("缺少保存标题");
        put(sections, key, raw.substring(start).strip());
        JsonNode result = read(contract, "", sections);
        if (!sections.isEmpty()) throw invalid("未知或未消费标题 " + sections.keySet().iterator().next());
        return result.toString();
    }

    private static void put(Map<String, String> sections, String key, String value) {
        if (sections.putIfAbsent(key, value) != null) throw invalid("重复标题 " + key);
    }

    private static JsonNode read(JsonNode schema, String path, Map<String, String> sections) {
        String type = type(schema);
        if (permitsNull(schema) && "null".equals(sections.get(path))) {
            sections.remove(path);
            if (sections.keySet().stream().anyMatch(k -> k.startsWith(path + "/")))
                throw invalid(path + " 同时声明 null 和内容");
            return JSON.nullNode();
        }
        if (type.equals("object")) {
            ObjectNode object = JSON.createObjectNode();
            schema.path("properties").fields().forEachRemaining(field -> {
                String child = path.isEmpty() ? field.getKey() : path + "/" + field.getKey();
                boolean present = sections.keySet().stream().anyMatch(k -> k.equals(child) || k.startsWith(child + "/"));
                boolean required = false;
                for (JsonNode item : schema.path("required")) if (item.asText().equals(field.getKey())) required = true;
                if (present || required) object.set(field.getKey(), read(field.getValue(), child, sections));
            });
            return object;
        }
        String value = sections.remove(path);
        if (value != null && value.equals("null") && permitsNull(schema)) return JSON.nullNode();
        if (type.equals("array")) {
            ArrayNode array = JSON.createArrayNode();
            if (type(schema.path("items")).equals("object")) {
                if (value == null && sections.keySet().stream().noneMatch(k -> k.startsWith(path + "/")))
                    throw invalid("缺少标题 " + path);
                if (value != null && !empty(value)) throw invalid(path + " 的记录须分标题填写");
                if (value != null && sections.keySet().stream().anyMatch(k -> k.startsWith(path + "/")))
                    throw invalid(path + " 同时声明空列表和记录");
                for (int i = 1; ; i++) {
                    String prefix = path + "/" + i;
                    if (sections.keySet().stream().noneMatch(k -> k.startsWith(prefix + "/"))) break;
                    array.add(read(schema.path("items"), prefix, sections));
                }
            } else {
                if (value == null) throw invalid("缺少标题 " + path);
                if (!empty(value)) for (String line : value.split("\\R")) {
                    if (!line.isBlank()) array.add(line.replaceFirst("^\\s*- ", ""));
                }
            }
            if (array.size() < schema.path("minItems").asInt(0)) throw invalid(path + " 记录不足");
            return array;
        }
        if (value == null) throw invalid("缺少标题 " + path);
        if (value.equals("null") && permitsNull(schema)) return JSON.nullNode();
        if (type.equals("string")) {
            if (schema.has("enum")) {
                boolean found = false;
                for (JsonNode item : schema.path("enum")) if (item.asText().equals(value)) found = true;
                if (!found) throw invalid(path + " 可选值无效");
            }
            return JSON.getNodeFactory().textNode(value);
        }
        try {
            if (type.equals("integer")) return JSON.getNodeFactory().numberNode(Long.parseLong(value));
            if (type.equals("number")) return JSON.getNodeFactory().numberNode(new java.math.BigDecimal(value));
            if (type.equals("boolean") && (value.equals("true") || value.equals("false")))
                return JSON.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
        } catch (NumberFormatException exception) { throw invalid(path + " 数值无效"); }
        throw invalid(path + " 类型无效");
    }

    private boolean freeText() {
        return contract.path("properties").size() == 1 && contract.path("properties").has("text");
    }
    private static String type(JsonNode node) {
        JsonNode type = node.path("type");
        if (type.isArray()) for (JsonNode item : type) if (!item.asText().equals("null")) return item.asText();
        return type.asText("string");
    }
    private static boolean permitsNull(JsonNode node) {
        for (JsonNode item : node.path("type")) if (item.asText().equals("null")) return true;
        return false;
    }
    private static boolean empty(String value) { return value.isBlank() || value.equals("无"); }
    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException("纯文本响应无法保存：" + detail);
    }
}
