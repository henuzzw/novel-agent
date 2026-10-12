package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.Set;
import java.util.function.Function;

/** Shared wire contract; each transport supplies its own task-scoped, authorized material reader. */
public final class CodexMaterialReadTool {
    public static final String NAME = "novel_read_material";
    private CodexMaterialReadTool() { }

    public static ArrayNode definitions(ObjectMapper json) {
        var tools = json.createArrayNode();
        var tool = tools.addObject().put("type", "function").put("name", NAME)
                .put("description", "读取本轮明确授权的完整小说资料。按字符位置分页；返回原文与下一位置，不允许跨项目或修改。");
        var schema = tool.putObject("inputSchema").put("type", "object").put("additionalProperties", false);
        schema.putArray("required").add("material_id").add("offset");
        var properties = schema.putObject("properties");
        properties.putObject("material_id").put("type", "string").put("pattern", "^[a-f0-9]{64}$");
        properties.putObject("offset").put("type", "integer").put("minimum", 0);
        return tools;
    }

    public static JsonNode respond(ObjectMapper json, JsonNode params, Set<String> authorized,
            Function<String, String> read) {
        var result = json.createObjectNode();
        try {
            var args = params.path("arguments");
            String id = args.path("material_id").asText();
            if (!NAME.equals(params.path("tool").asText()) || !params.path("namespace").asText("").isEmpty()
                    || !args.isObject() || args.size() != 2 || !id.matches("[a-f0-9]{64}")
                    || !authorized.contains(id) || !args.path("offset").isIntegralNumber()
                    || !args.path("offset").canConvertToLong())
                throw new IllegalArgumentException("未授权的资料读取");
            long offset = args.path("offset").asLong();
            String content = read.apply(id);
            if (offset < 0 || offset >= content.length()) throw new IllegalArgumentException("资料位置超出范围");
            int start = (int) offset;
            if (start > 0 && Character.isLowSurrogate(content.charAt(start))
                    && Character.isHighSurrogate(content.charAt(start - 1)))
                throw new IllegalArgumentException("资料位置不可拆分字符");
            int end = Math.min(content.length(), start + 6000);
            if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
            String text = "total_characters=" + content.length() + "; offset=" + start + "; next_offset="
                    + (end == content.length() ? "END" : end) + "\n" + content.substring(start, end);
            result.put("success", true).putArray("contentItems").addObject().put("type", "inputText").put("text", text);
        } catch (RuntimeException error) {
            result.put("success", false).putArray("contentItems").addObject().put("type", "inputText")
                    .put("text", "资料读取失败：仅允许读取本次授权资料及有效位置，不得猜测缺失内容。");
        }
        return result;
    }
}
