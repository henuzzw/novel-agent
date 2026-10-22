package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.*;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.writing.domain.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomationDraftPublisherTest {
    private final ManuscriptContent body = new ManuscriptContent("标题", "她递出纸条。", "摘要", List.of());
    private DraftLoopRun loop(int rounds) {
        UUID project = UUID.randomUUID();
        var manuscript = ManuscriptVersion.create(UUID.randomUUID(), project, null, 1, 1, "DEEPSEEK", null, null, body, List.of());
        var loop = DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, false, rounds,
                new DraftLoopRun.Basis("来源", "资料", null), manuscript);
        loop.claim(); return loop;
    }
    private DraftCheck issue() { return new DraftCheck("问题", List.of(new DraftCheck.Issue("F1", QualityDimension.FLUENCY,
            "建议", body.body(), "已有依据", "", "", "", "修改建议"))); }

    @Test void clearBOrCRejectingEveryIssueCanPublishButDeferredNoChangeCannot() {
        var clear = loop(3); clear.checked(body, new DraftCheck("无问题", List.of()));
        assertThat(AutomationDraftPublisher.eligible(clear)).isTrue();
        for (var verdict : List.of(DraftJudgment.Verdict.REJECT, DraftJudgment.Verdict.DEFER)) {
            var loop = loop(3); loop.checked(body, issue());
            loop.judged(new DraftJudgment(DraftJudgment.Action.NO_CHANGE,
                    List.of(new DraftJudgment.Decision("F1", verdict, "裁决依据")), null, List.of()));
            assertThat(AutomationDraftPublisher.eligible(loop)).isEqualTo(verdict == DraftJudgment.Verdict.REJECT);
        }
    }
    @Test void capContextAndCycleStopsAreNotAutomaticPublicationApproval() {
        for (var reason : List.of(DraftLoopRun.StopReason.ROUND_LIMIT, DraftLoopRun.StopReason.NEEDS_CONTEXT,
                DraftLoopRun.StopReason.NO_PROGRESS, DraftLoopRun.StopReason.CYCLE_DETECTED, DraftLoopRun.StopReason.SOURCE_CHANGED)) {
            var loop = loop(1); loop.stop(reason); assertThat(AutomationDraftPublisher.eligible(loop)).isFalse();
        }
    }
}
