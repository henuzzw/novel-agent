package com.novelagent.agent.application;

import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterContractReviewResponse;
import com.novelagent.writing.api.ChapterReviewResponse;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.QualityReviewResponse;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ReviewStatus;
import com.novelagent.planning.application.ModelProvider;
import java.util.List;
import java.util.UUID;

public class ChapterAutomationPlanner {
    public enum Action { CONTRACT, CONTRACT_REVIEW, MANUSCRIPT, QUALITY_REVIEW, QUALITY_REVISION, REVIEW, WAIT, NEXT_CHAPTER }

    public record Decision(Action action, String reason, List<String> issueIds) {
        public Decision(Action action, String reason) { this(action, reason, List.of()); }
    }

    public Decision next(UUID outlineId, ChapterContractResponse contract,
            ChapterContractReviewResponse contractReview, ManuscriptResponse manuscript,
            ChapterReviewResponse review, UUID canonManuscriptId) {
        return next(outlineId, contract, contractReview, manuscript, review, canonManuscriptId, false, null);
    }

    public Decision next(UUID outlineId, ChapterContractResponse contract,
            ChapterContractReviewResponse contractReview, ManuscriptResponse manuscript,
            ChapterReviewResponse review, UUID canonManuscriptId, boolean qualityReviewEnabled,
            QualityReviewResponse qualityReview) {
        return next(outlineId, contract, contractReview, manuscript, review, canonManuscriptId,
                qualityReviewEnabled, qualityReview, 0, 0, ModelProvider.LOCAL_TEMPLATE);
    }

    public Decision next(UUID outlineId, ChapterContractResponse contract,
            ChapterContractReviewResponse contractReview, ManuscriptResponse manuscript,
            ChapterReviewResponse review, UUID canonManuscriptId, boolean qualityReviewEnabled,
            QualityReviewResponse qualityReview, int maxAutoRevisionRounds, int usedAutoRevisionRounds,
            ModelProvider provider) {
        if (canonManuscriptId != null) {
            return manuscript == null || canonManuscriptId.equals(manuscript.id())
                    ? new Decision(Action.NEXT_CHAPTER, null)
                    : waitFor("本章已有正史及更新稿，请先处理修订稿和正史替换");
        }
        if (contract == null) return new Decision(Action.CONTRACT, null);
        if (!outlineId.equals(contract.sourceOutlineVersionId())) {
            return waitFor("本章合同来自旧大纲，请重新生成合同并审阅");
        }
        if (contract.status() != ChapterContractStatus.APPROVED) {
            if (contractReview == null || !contract.id().equals(contractReview.sourceContractVersionId())
                    || contract.version() != contractReview.sourceContractRowVersion()) {
                return new Decision(Action.CONTRACT_REVIEW, null);
            }
            return waitFor("请处理合同审阅问题、确认审阅并确认章节合同");
        }
        if (manuscript == null) return new Decision(Action.MANUSCRIPT, null);
        if (!contract.id().equals(manuscript.sourceContractVersionId())) {
            return waitFor("正文来自旧合同，请按当前合同重新生成正文");
        }
        if (manuscript.status() != ManuscriptStatus.AUTHOR_ACCEPTED) {
            if (qualityReviewEnabled) {
                if (qualityReview == null || !qualityReview.current()
                        || !manuscript.id().equals(qualityReview.sourceManuscriptId())
                        || manuscript.version() != qualityReview.sourceManuscriptRowVersion()) {
                    return new Decision(Action.QUALITY_REVIEW, null);
                }
                int count = qualityReview.content().issues().size();
                if (count > 0 && maxAutoRevisionRounds > 0) {
                    if (qualityReview.content().issues().stream().anyMatch(issue ->
                            !"FLUENCY".equals(issue.category()) || !"INFO".equals(issue.severity()))) {
                        return waitFor("质量报告包含风格、逻辑、场景或较严重问题，请由作者选择修改，不自动采纳");
                    }
                    if (usedAutoRevisionRounds >= maxAutoRevisionRounds) {
                        return waitFor("已达到本章自动润色轮数上限，请由作者处理剩余建议并确认正文");
                    }
                    if (provider == ModelProvider.LOCAL_TEMPLATE) return waitFor("本地模板不能执行语义润色，请由作者处理");
                    return new Decision(Action.QUALITY_REVISION, null,
                            qualityReview.content().issues().stream().map(issue -> issue.id()).toList());
                }
                return waitFor(count == 0 ? "质量检查已完成；请阅读正文并由作者确认，未发现问题不代表质量保证"
                        : "质量检查提出 " + count + " 条建议，请选择是否润色，再由作者确认正文");
            }
            return waitFor("请由作者确认正文");
        }
        if (review == null || !manuscript.id().equals(review.sourceManuscriptVersionId())) {
            return new Decision(Action.REVIEW, null);
        }
        if (review.status() == ReviewStatus.RETURNED) return waitFor("审稿已打回，请完成正文修改并重新确认");
        if (review.content().issues().stream().anyMatch(issue -> "BLOCKING".equals(issue.severity()) && !issue.resolved())) {
            return waitFor("审稿存在未处理的阻断问题，请修改正文或处理问题");
        }
        if (review.status() != ReviewStatus.APPROVED) return waitFor("请处理候选事实并确认审稿结果");
        return waitFor("请由作者提交本章正史，再继续下一章");
    }

    private static Decision waitFor(String reason) {
        return new Decision(Action.WAIT, reason);
    }
}
