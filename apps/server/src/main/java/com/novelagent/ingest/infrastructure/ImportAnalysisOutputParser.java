package com.novelagent.ingest.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.ingest.domain.ImportAnalysis;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class ImportAnalysisOutputParser {
    private static final Set<String> NON_CLUES = Set.of("CHARACTER", "WORLD", "RELATIONSHIP", "EVENT");
    private static final Set<String> PROGRESS = Set.of("NOT_APPLICABLE", "SET_UP", "REINFORCED", "PAYOFF", "UNRESOLVED", "UNKNOWN");
    private final ObjectMapper mapper;

    public ImportAnalysisOutputParser(ObjectMapper mapper) { this.mapper = mapper; }

    public ImportAnalysis.Batch parse(String output) {
        if (output == null || output.isBlank()) throw invalid("根节点：响应为空", null);
        JsonNode root;
        try {
            root = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
        } catch (JsonProcessingException error) {
            throw invalid("根节点：不是完整、合法的 JSON", error);
        }
        if (root == null || !root.isObject() || !root.path("summary").isTextual() || !root.path("items").isArray()) {
            throw invalid("根节点：必须包含字符串 summary 和数组 items", null);
        }
        if (root.path("items").size() > 80) throw invalid("items：单段解析项不能超过 80 项", null);
        var keys = new HashSet<String>();
        for (int i = 0; i < root.path("items").size(); i++) {
            JsonNode item = root.path("items").get(i);
            String path = "items[" + i + "]";
            for (String field : Set.of("key", "category", "certainty", "title", "description", "progress")) {
                if (!item.path(field).isTextual()) throw invalid(path + "." + field + "：必须是字符串", null);
            }
            // Keys are technical handles, not evidence; normalize dotted notation without changing story data.
            String key = item.path("key").asText().replace('.', '_');
            if (!key.matches("[A-Za-z0-9_-]{1,76}")) {
                throw invalid(path + ".key：解析项标识无效；须为1至76位英文字母、数字、下划线或短横线", null);
            }
            if (!keys.add(key)) throw invalid(path + ".key：解析项标识重复（含点号归一化后的冲突），不能合并或覆盖条目", null);
            ((ObjectNode) item).put("key", key);
            if (!item.path("subjects").isArray() || !item.path("evidence").isArray()) {
                throw invalid(path + "：subjects 和 evidence 必须是数组", null);
            }
            for (var subject : item.path("subjects")) {
                if (!subject.isTextual()) throw invalid(path + ".subjects：人物名称必须是字符串", null);
            }
            for (int j = 0; j < item.path("evidence").size(); j++) {
                var evidence = item.path("evidence").get(j);
                if (!evidence.path("chapterId").isTextual() || !evidence.path("quote").isTextual()
                        || !evidence.path("occurrence").isIntegralNumber() || !evidence.path("occurrence").canConvertToInt()) {
                    throw invalid(path + ".evidence[" + j + "]：必须包含章节 UUID、字符串引文和整数出现序号", null);
                }
            }
            String category = item.path("category").asText();
            if (!NON_CLUES.contains(category) && !Set.of("CLUE", "FORESHADOW").contains(category)) {
                throw invalid(path + ".category：必须是人物、世界、关系、事件、线索或伏笔的规定分类；UNKNOWN 只能用于 certainty 或线索 progress", null);
            }
            if (!PROGRESS.contains(item.path("progress").asText())) {
                throw invalid(path + ".progress：不是有效的线索进度", null);
            }
            // Progress has no meaning outside clues; never promote certainty or invent a payoff.
            if (NON_CLUES.contains(category)) ((ObjectNode) item).put("progress", "NOT_APPLICABLE");
        }
        try {
            return mapper.treeToValue(root, ImportAnalysis.Batch.class);
        } catch (JsonMappingException error) {
            StringBuilder path = new StringBuilder();
            for (var ref : error.getPath()) {
                if (ref.getFieldName() != null) {
                    if (!path.isEmpty()) path.append('.');
                    path.append(ref.getFieldName());
                } else if (ref.getIndex() >= 0) path.append('[').append(ref.getIndex()).append(']');
            }
            Throwable cause = error;
            while (cause.getCause() != null) cause = cause.getCause();
            String reason = cause instanceof IllegalArgumentException ? cause.getMessage() : "字段类型或值不符合解析结构";
            throw invalid((path.isEmpty() ? "根节点" : path) + "：" + reason, error);
        } catch (JsonProcessingException error) {
            throw invalid("根节点：字段类型或值不符合解析结构", error);
        }
    }

    private static IllegalArgumentException invalid(String detail, Throwable cause) {
        return new IllegalArgumentException("原文解析输出校验失败：" + detail, cause);
    }
}
