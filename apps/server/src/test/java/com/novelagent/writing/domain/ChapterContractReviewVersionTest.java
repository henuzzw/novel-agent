package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChapterContractReviewVersionTest {
    @Test
    void blockingIssueRequiresResolutionBeforeApproval() {
        ReviewIssue issue = new ReviewIssue("1", "BLOCKING", "连续性", "座位不符", "合同原文", "修正位置", false);
        ChapterContractReviewVersion review = ChapterContractReviewVersion.create(UUID.randomUUID(),
                UUID.randomUUID(), 2, UUID.randomUUID(), 3, 1, "LOCAL_TEMPLATE", null,
                new ChapterContractReviewContent("发现座位冲突", List.of(issue)));

        assertThatThrownBy(review::approve).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("阻断问题");
        assertThatThrownBy(() -> review.revise(new ChapterContractReviewContent("作者已核对", List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可改写");
        review.revise(new ChapterContractReviewContent("发现座位冲突", List.of(
                new ReviewIssue("1", "BLOCKING", "连续性", "座位不符", "合同原文", "修正位置", true))));
        review.approve();

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThatThrownBy(review::approve).isInstanceOf(IllegalStateException.class);
    }
}
