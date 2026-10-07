package com.novelagent.platform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class HttpLogSanitizer {
    private static final Set<String> PRIVATE_TEXT = Set.of("body", "text", "content", "quote", "sourcetext",
            "sample", "sampletext", "instruction", "instructions", "prompt", "systemprompt", "userprompt",
            "guidance", "defaultsystemprompt", "protectedrules",
            "responsetext", "detail", "errordetail", "errormessage", "planningerror", "excerpt", "snippet");
    private final ObjectMapper mapper;
    public HttpLogSanitizer(ObjectMapper mapper) { this.mapper = mapper; }

    public String summarize(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[] bytes) return "[BINARY bytes=" + bytes.length + "]";
        if (value instanceof CharSequence text) return "[TEXT chars=" + text.length() + "]";
        try {
            JsonNode safe = sanitize(mapper.valueToTree(value), "", 0, new int[] {0});
            String json = mapper.writeValueAsString(safe);
            // Never truncate before redacting: a cut JSON value could conceal its sensitive key.
            return json.length() <= 4000 ? json : json.substring(0, 4000) + "...[TRUNCATED]";
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException error) {
            return "[UNAVAILABLE]";
        }
    }

    private JsonNode sanitize(JsonNode node, String key, int depth, int[] nodes) {
        if (++nodes[0] > 300 || depth > 8) return TextNode.valueOf("[LIMIT]");
        String field = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (field.contains("password") || field.contains("secret") || field.contains("token")
                && !field.endsWith("tokens") && !field.equals("tokensource")
                || field.contains("apikey") || field.equals("authorization") || field.equals("cookie")
                || field.equals("setcookie") || field.equals("credential") || field.equals("auth")) {
            return TextNode.valueOf("[REDACTED]");
        }
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            var fields = node.fields();
            while (fields.hasNext() && nodes[0] <= 300) {
                var entry = fields.next();
                result.set(safeString(entry.getKey()), sanitize(entry.getValue(), entry.getKey(), depth + 1, nodes));
            }
            if (fields.hasNext()) result.put("_logLimit", true);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            int count = Math.min(20, node.size());
            for (int i = 0; i < count && nodes[0] <= 300; i++) result.add(sanitize(node.get(i), key, depth + 1, nodes));
            if (result.size() < node.size()) result.add("[MORE total=" + node.size() + "]");
            return result;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (PRIVATE_TEXT.contains(field) || text.length() > 512) return TextNode.valueOf("[TEXT chars=" + text.length() + "]");
            return TextNode.valueOf(safeString(text));
        }
        return node;
    }

    private String safeString(String text) {
        String safe = text.replaceAll("(?i)Bearer\\s+[^\\s,;\"}]+", "Bearer [REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization)([\\s\"']*[:=][\\s\"']*)[^\\s\"',;}]+", "$1$2[REDACTED]")
                .replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[REDACTED]@")
                .replaceAll("[\\r\\n\\t\\p{Cntrl}]", " ");
        return safe.length() <= 512 ? safe : safe.substring(0, 512) + "...";
    }
}
