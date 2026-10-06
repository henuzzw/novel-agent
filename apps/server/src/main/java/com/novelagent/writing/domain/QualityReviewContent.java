package com.novelagent.writing.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record QualityReviewContent(String summary, List<QualityScore> scores, List<ReviewIssue> issues) {
    public QualityReviewContent {
        if (summary == null || summary.isBlank() || scores == null || issues == null) {
            throw new IllegalArgumentException("质量报告缺少摘要、评分或问题清单");
        }
        Set<QualityDimension> dimensions = new HashSet<>();
        for (QualityScore score : scores) {
            if (score == null || score.dimension() == null || !dimensions.add(score.dimension())
                    || score.rationale() == null || score.rationale().isBlank()
                    || (score.score() != null && (score.score() < 0 || score.score() > 100))) {
                throw new IllegalArgumentException("质量评分的维度、范围或依据不合法");
            }
        }
        if (dimensions.size() != QualityDimension.values().length) {
            throw new IllegalArgumentException("质量报告必须覆盖风格、通顺、逻辑和场景四个维度");
        }
        if (issues.size() > 20) throw new IllegalArgumentException("质量问题不能超过 20 条");
        Set<String> ids = new HashSet<>();
        for (ReviewIssue issue : issues) {
            if (issue == null || issue.id() == null || issue.id().isBlank() || !ids.add(issue.id())
                    || !Set.of("WARNING", "INFO").contains(issue.severity())
                    || issue.description() == null || issue.description().isBlank()
                    || issue.evidence() == null || issue.evidence().isBlank()
                    || issue.suggestion() == null || issue.suggestion().isBlank() || issue.resolved()) {
                throw new IllegalArgumentException("质量问题必须包含唯一编号、原文证据和建议，且不能自行放行门禁");
            }
            QualityDimension.valueOf(issue.category());
        }
        scores = List.copyOf(scores);
        issues = List.copyOf(issues);
    }

    public void requireEvidenceIn(String body) {
        for (ReviewIssue issue : issues) {
            if (!body.contains(issue.evidence())) throw new IllegalArgumentException("质量问题证据不在当前正文中：" + issue.id());
        }
    }
}
