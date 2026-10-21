package com.novelagent.writing.domain;

import static com.novelagent.platform.support.Text.isBlank;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;

/** B 的意见是候选，不是事实；证据只允许来自当前稿。 */
public record DraftCheck(String summary, List<Issue> issues) {
    public static final int MAX_EVIDENCE_QUOTES = 6;

    public record Evidence(String quote) {
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Evidence fromJson(JsonNode value) {
            // Saved single-quote reports remain readable; new reports use explicit quote objects.
            if (value.isTextual()) return new Evidence(value.textValue());
            if (value.isObject() && value.size() == 1 && value.path("quote").isTextual()) {
                return new Evidence(value.get("quote").textValue());
            }
            throw new IllegalArgumentException("检查证据格式无效：每段只允许quote原文字段");
        }
    }

    public record Issue(String id, QualityDimension category, String description,
            @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<Evidence> evidence,
            String existingBasis, String gap, String candidateDesign, String impact, String suggestion) {
        public Issue {
            if (evidence == null || evidence.isEmpty() || evidence.size() > MAX_EVIDENCE_QUOTES
                    || evidence.stream().anyMatch(e -> e == null || isBlank(e.quote()))) {
                throw new IllegalArgumentException("检查证据缺少原文引文或超过" + MAX_EVIDENCE_QUOTES + "段");
            }
            evidence = List.copyOf(evidence);
        }

        public Issue(String id, QualityDimension category, String description, String evidence,
                String existingBasis, String gap, String candidateDesign, String impact, String suggestion) {
            this(id, category, description, List.of(new Evidence(evidence)),
                    existingBasis, gap, candidateDesign, impact, suggestion);
        }
    }

    public DraftCheck {
        if (isBlank(summary) || issues == null || issues.size() > 20) {
            throw new IllegalArgumentException("检查报告缺少摘要或问题清单（最多20条）");
        }
        var ids = new HashSet<String>();
        for (var issue : issues) {
            if (issue == null || isBlank(issue.id()) || !ids.add(issue.id())
                    || issue.category() == null || isBlank(issue.description())
                    || isBlank(issue.suggestion())
                    || issue.existingBasis() == null || issue.gap() == null || issue.candidateDesign() == null || issue.impact() == null) {
                throw new IllegalArgumentException("检查问题缺少编号、证据、依据或补丁说明");
            }
        }
        issues = List.copyOf(issues);
    }

    public void requireEvidenceIn(String body) {
        for (var issue : issues) {
            for (int index = 0; index < issue.evidence().size(); index++) {
                if (!body.contains(issue.evidence().get(index).quote())) {
                    throw new IllegalArgumentException("检查证据不在当前正文中：" + issue.id()
                            + " · evidence[" + index + "]（第" + (index + 1) + "段），须引用当前正文的连续原文");
                }
            }
        }
    }
}
