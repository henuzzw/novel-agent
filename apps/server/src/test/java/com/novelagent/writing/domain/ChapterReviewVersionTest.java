package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChapterReviewVersionTest {
    @Test
    void requiresIssuesAndFactsToBeDecidedBeforeApproval() {
        ChapterReviewVersion review = version(new ChapterReviewContent("需要处理", List.of(
                new ReviewIssue("I1", "BLOCKING", "CONTINUITY", "冲突", "证据", "修复", false)), List.of(
                new FactProposal("F1", "EVENT", "主角", "发现", "线索", "证据", FactDecision.PENDING))));
        assertThatThrownBy(review::approve).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("阻断");

        review.revise(new ChapterReviewContent("已处理", List.of(
                new ReviewIssue("I1", "BLOCKING", "CONTINUITY", "冲突", "证据", "修复", true)), List.of(
                new FactProposal("F1", "EVENT", "主角", "发现", "线索", "证据", FactDecision.ACCEPTED))));
        review.approve();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void blocksAcceptedFactWithUnresolvedPronoun() {
        FactProposal unresolved = new FactProposal("F1", "STATE_CHANGE", "她", "位置", "图书馆", "证据",
                0.8, statePayload("她", null), FactDecision.ACCEPTED);
        ChapterReviewVersion review = version(new ChapterReviewContent("待消歧", List.of(), List.of(unresolved)));

        assertThatThrownBy(review::approve)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未解析的人物代词");

        FactProposal resolved = new FactProposal("F1", "STATE_CHANGE", "她", "位置", "图书馆", "证据",
                0.8, statePayload("她", UUID.randomUUID().toString()), FactDecision.ACCEPTED);
        review.revise(new ChapterReviewContent("已消歧", List.of(), List.of(resolved)));
        review.approve();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void approvesStateFactWhenTypedNameIsNullButStableEntityIdExists() {
        FactProposal resolved = new FactProposal("F1", "STATE_CHANGE", "许言川", "位置", "教室第二排", "证据",
                0.9, statePayload(null, UUID.randomUUID().toString()), FactDecision.ACCEPTED);
        ChapterReviewVersion review = version(new ChapterReviewContent("已绑定人物", List.of(), List.of(resolved)));

        review.approve();

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void checksFactSubjectWhenTypedNameIsMissing() {
        FactProposal unresolved = new FactProposal("F1", "STATE_CHANGE", "她", "位置", "教室第二排", "证据",
                0.9, statePayload(null, null), FactDecision.ACCEPTED);
        ChapterReviewVersion review = version(new ChapterReviewContent("仍需消歧", List.of(), List.of(unresolved)));

        assertThatThrownBy(review::approve)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未解析的人物代词");
    }

    private TypedFactPayload statePayload(String name, String entityId) {
        return new TypedFactPayload("CHARACTER", name, null, null, "当晚", null,
                "character.location", null, "图书馆", null, null, null, null, null,
                null, null, null, null, null, null, entityId, null, null, null, "CHARACTER");
    }

    private ChapterReviewVersion version(ChapterReviewContent content) {
        return ChapterReviewVersion.create(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                1, "LOCAL_TEMPLATE", null, content);
    }
}
