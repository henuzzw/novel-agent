package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterContractReviewResponse;
import com.novelagent.writing.api.ChapterReviewResponse;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.QualityReviewResponse;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityDimension;
import com.novelagent.writing.domain.QualityScore;
import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.ReviewStatus;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChapterAutomationPlannerTest {
    static final UUID PROJECT = UUID.randomUUID();
    static final UUID OUTLINE = UUID.randomUUID();
    static final UUID CONTRACT = UUID.randomUUID();
    static final UUID MANUSCRIPT = UUID.randomUUID();
    private final ChapterAutomationPlanner planner = new ChapterAutomationPlanner();

    @Test
    void generatesContractAndRequiresCurrentContractReview() {
        assertThat(next(null, null, null, null).action()).isEqualTo(ChapterAutomationPlanner.Action.CONTRACT);
        var draft = contract(ChapterContractStatus.DRAFT);
        assertThat(next(draft, null, null, null).action()).isEqualTo(ChapterAutomationPlanner.Action.CONTRACT_REVIEW);
        var review = contractReview(CONTRACT, 2);
        assertThat(next(draft, review, null, null).action()).isEqualTo(ChapterAutomationPlanner.Action.WAIT);
        assertThat(next(draft, contractReview(CONTRACT, 1), null, null).action())
                .isEqualTo(ChapterAutomationPlanner.Action.CONTRACT_REVIEW);
        assertThat(next(draft, contractReview(UUID.randomUUID(), 2), null, null).action())
                .isEqualTo(ChapterAutomationPlanner.Action.CONTRACT_REVIEW);
    }

    @Test
    void requiresAuthorAcceptanceBeforeReviewAndRejectsStaleSource() {
        var approved = contract(ChapterContractStatus.APPROVED);
        assertThat(next(approved, null, null, null).action()).isEqualTo(ChapterAutomationPlanner.Action.MANUSCRIPT);
        assertThat(next(approved, null, manuscript(ManuscriptStatus.DRAFT), null).reason()).contains("作者确认");
        assertThat(next(approved, null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED), null).action())
                .isEqualTo(ChapterAutomationPlanner.Action.REVIEW);
        assertThat(next(approved, null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED), review(UUID.randomUUID(), List.of())).action())
                .isEqualTo(ChapterAutomationPlanner.Action.REVIEW);
        assertThat(planner.next(UUID.randomUUID(), approved, null, null, null, null).reason()).contains("旧大纲");
    }

    @Test
    void checksDraftBeforeWaitingAndRejectsOldQualitySources() {
        var contract = contract(ChapterContractStatus.APPROVED);
        var draft = manuscript(ManuscriptStatus.DRAFT);
        assertThat(planner.next(OUTLINE, contract, null, draft, null, null, true, null).action())
                .isEqualTo(ChapterAutomationPlanner.Action.QUALITY_REVIEW);
        for (var report : List.of(quality(MANUSCRIPT, 0, false, List.of()),
                quality(UUID.randomUUID(), 0, true, List.of()), quality(MANUSCRIPT, 1, true, List.of()))) {
            assertThat(planner.next(OUTLINE, contract, null, draft, null, null, true, report).action())
                    .isEqualTo(ChapterAutomationPlanner.Action.QUALITY_REVIEW);
        }
        assertThat(planner.next(OUTLINE, contract, null, draft, null, null, true, quality(MANUSCRIPT, 0, true, List.of())).reason())
                .contains("作者确认", "不代表质量保证");
        var issue = new ReviewIssue("Q1", "INFO", "SCENE", "罗列过程", "原文", "呈现场景", false);
        assertThat(planner.next(OUTLINE, contract, null, draft, null, null, true, quality(MANUSCRIPT, 0, true, List.of(issue))).reason())
                .contains("1 条建议", "选择是否润色");
    }

    @Test
    void confirmedOrCommittedManuscriptDoesNotRepeatLiteraryCheckAndLegacyPolicyStaysOff() {
        var contract = contract(ChapterContractStatus.APPROVED);
        assertThat(planner.next(OUTLINE, contract, null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED), null, null, true, null).action())
                .isEqualTo(ChapterAutomationPlanner.Action.REVIEW);
        assertThat(planner.next(OUTLINE, contract, null, manuscript(ManuscriptStatus.DRAFT), null, null, false, null).reason())
                .isEqualTo("请由作者确认正文");
        assertThat(planner.next(OUTLINE, contract, null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED), null, MANUSCRIPT, true, null).action())
                .isEqualTo(ChapterAutomationPlanner.Action.NEXT_CHAPTER);
    }

    @Test
    void stopsForBlockingIssuesAndDoesNotAutoApproveOrCommit() {
        var issue = new ReviewIssue("issue", "BLOCKING", "LOGIC", "逻辑矛盾", "证据", "修改", false);
        assertThat(next(contract(ChapterContractStatus.APPROVED), null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED),
                review(MANUSCRIPT, List.of(issue))).reason()).contains("阻断");
        assertThat(next(contract(ChapterContractStatus.APPROVED), null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED),
                review(MANUSCRIPT, List.of())).reason()).contains("确认审稿");
        assertThat(planner.next(OUTLINE, null, null, manuscript(ManuscriptStatus.AUTHOR_ACCEPTED), null, MANUSCRIPT).action())
                .isEqualTo(ChapterAutomationPlanner.Action.NEXT_CHAPTER);
        assertThat(planner.next(OUTLINE, null, null, manuscript(ManuscriptStatus.DRAFT), null, UUID.randomUUID()).reason())
                .contains("正史替换");
    }

    private ChapterAutomationPlanner.Decision next(ChapterContractResponse contract,
            ChapterContractReviewResponse contractReview, ManuscriptResponse manuscript, ChapterReviewResponse review) {
        return planner.next(OUTLINE, contract, contractReview, manuscript, review, null);
    }

    static ChapterContractResponse contract(ChapterContractStatus status) {
        return new ChapterContractResponse(CONTRACT, PROJECT, OUTLINE, null, 1, 1, "chapter-contract/1", status,
                "LOCAL_TEMPLATE", null, null, 2, null, null);
    }

    static ChapterContractReviewResponse contractReview(UUID source, long rowVersion) {
        return new ChapterContractReviewResponse(UUID.randomUUID(), PROJECT, 1, source, rowVersion, 1,
                ReviewStatus.DRAFT, "LOCAL_TEMPLATE", null, new ChapterContractReviewContent("通过", List.of()), 0, null, null);
    }

    static ManuscriptResponse manuscript(ManuscriptStatus status) {
        return new ManuscriptResponse(MANUSCRIPT, PROJECT, CONTRACT, null, null, 1, 1, "manuscript/1",
                status, "LOCAL_TEMPLATE", null, null, List.of(), 0, null, null);
    }

    static ChapterReviewResponse review(UUID source, List<ReviewIssue> issues) {
        return new ChapterReviewResponse(UUID.randomUUID(), PROJECT, 1, source, 1, "chapter-review/2", ReviewStatus.DRAFT,
                "LOCAL_TEMPLATE", null, new ChapterReviewContent("审稿", issues, List.of()), 0, null, null);
    }

    static QualityReviewResponse quality(UUID source, long rowVersion, boolean current, List<ReviewIssue> issues) {
        var scores = java.util.Arrays.stream(QualityDimension.values()).map(d -> new QualityScore(d, null, "本地未评分")).toList();
        return new QualityReviewResponse(UUID.randomUUID(), PROJECT, 1, 1, source, rowVersion, "LOCAL_TEMPLATE", current,
                new QualityReviewContent("本地检查", scores, issues), null);
    }

    @Test void automaticRevisionSelectsOnlyMinorFluencyIssuesAndIsBounded() {
        var issue = new ReviewIssue("Q1", "INFO", "FLUENCY", "标点重复", "。。", "复核标点", false);
        var decision = auto(quality(MANUSCRIPT, 0, true, List.of(issue)), 0);
        assertThat(decision.action()).isEqualTo(ChapterAutomationPlanner.Action.QUALITY_REVISION);
        assertThat(decision.issueIds()).containsExactly("Q1");
        assertThat(auto(quality(MANUSCRIPT, 0, true, List.of(issue)), 1).reason()).contains("轮数上限");
        assertThat(auto(quality(MANUSCRIPT, 0, true, List.of()), 1).reason()).contains("作者确认");
        assertThat(auto(quality(MANUSCRIPT, 0, false, List.of(issue)), 1).action())
                .isEqualTo(ChapterAutomationPlanner.Action.QUALITY_REVIEW);
    }

    @Test void mixedOrRiskyIssuesAlwaysWaitForAuthorInsteadOfPartialAutoAdoption() {
        var safe = new ReviewIssue("Q1", "INFO", "FLUENCY", "标点重复", "。。", "复核标点", false);
        for (var risky : List.of(new ReviewIssue("Q2", "WARNING", "FLUENCY", "语义歧义", "原文", "复核", false),
                new ReviewIssue("Q2", "INFO", "LOGIC", "矛盾", "原文", "复核", false),
                new ReviewIssue("Q2", "INFO", "SCENE", "流水账", "原文", "复核", false),
                new ReviewIssue("Q2", "INFO", "STYLE", "风格不符", "原文", "复核", false))) {
            assertThat(auto(quality(MANUSCRIPT, 0, true, List.of(safe, risky)), 0).action())
                    .isEqualTo(ChapterAutomationPlanner.Action.WAIT);
        }
    }

    private ChapterAutomationPlanner.Decision auto(QualityReviewResponse report, int used) {
        return planner.next(OUTLINE, contract(ChapterContractStatus.APPROVED), null, manuscript(ManuscriptStatus.DRAFT),
                null, null, true, report, 1, used, com.novelagent.planning.application.ModelProvider.DEEPSEEK);
    }
}
