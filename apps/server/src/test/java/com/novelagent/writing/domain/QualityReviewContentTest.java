package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class QualityReviewContentTest {
    private List<QualityScore> scores() {
        return Arrays.stream(QualityDimension.values()).map(d -> new QualityScore(d, null, "本地不评分")).toList();
    }
    private ReviewIssue issue(String severity, String evidence) {
        return new ReviewIssue("Q1", severity, "FLUENCY", "复核语句", evidence, "删除重复标点", false);
    }
    @Test void acceptsFourDimensionsAndLiteralEvidenceWithoutGuaranteeingQuality() {
        var report = new QualityReviewContent("仅供参考", scores(), List.of(issue("INFO", "。。")));
        report.requireEvidenceIn("他停下。。");
        assertThat(report.scores()).allMatch(s -> s.score() == null);
        assertThatThrownBy(() -> report.requireEvidenceIn("他停下。" )).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsIncompleteDuplicateOutOfRangeAndBlockingResults() {
        assertThatThrownBy(() -> new QualityReviewContent("摘要", scores().subList(0, 3), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QualityReviewContent("摘要", List.of(new QualityScore(QualityDimension.STYLE, 101, "依据")), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QualityReviewContent("摘要", scores(), List.of(issue("BLOCKING", "证据")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QualityReviewContent("摘要", scores(), List.of(issue("INFO", "证据"), issue("INFO", "证据")))).isInstanceOf(IllegalArgumentException.class);
    }
}
