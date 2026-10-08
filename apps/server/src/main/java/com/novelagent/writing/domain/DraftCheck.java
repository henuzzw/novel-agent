package com.novelagent.writing.domain;

import java.util.HashSet;
import java.util.List;

/** B 的意见是候选，不是事实；证据只允许来自当前稿。 */
public record DraftCheck(String summary, List<Issue> issues) {
    public record Issue(String id, QualityDimension category, String description, String evidence,
            String existingBasis, String gap, String candidateDesign, String impact, String suggestion) { }

    public DraftCheck {
        if (summary == null || summary.isBlank() || issues == null || issues.size() > 20) {
            throw new IllegalArgumentException("检查报告缺少摘要或问题清单（最多20条）");
        }
        var ids = new HashSet<String>();
        for (var issue : issues) {
            if (issue == null || issue.id() == null || issue.id().isBlank() || !ids.add(issue.id())
                    || issue.category() == null || issue.description() == null || issue.description().isBlank()
                    || issue.evidence() == null || issue.evidence().isBlank()
                    || issue.suggestion() == null || issue.suggestion().isBlank()
                    || issue.existingBasis() == null || issue.gap() == null || issue.candidateDesign() == null || issue.impact() == null) {
                throw new IllegalArgumentException("检查问题缺少编号、证据、依据或补丁说明");
            }
        }
        issues = List.copyOf(issues);
    }

    public void requireEvidenceIn(String body) {
        for (var issue : issues) {
            if (!body.contains(issue.evidence())) throw new IllegalArgumentException("检查证据不在当前正文中：" + issue.id());
        }
    }
}
