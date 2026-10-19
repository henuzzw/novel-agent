package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DraftLoopRunTest {
    private final UUID project = UUID.randomUUID();
    private ManuscriptContent content(String body) { return new ManuscriptContent("标题", body, "摘要", List.of()); }
    private ManuscriptVersion manuscript(String body, int version) {
        return ManuscriptVersion.create(UUID.randomUUID(), project, null, 1, version, "DEEPSEEK", null, null, content(body), List.of());
    }
    private DraftLoopRun run(int cap) {
        var run = DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, false, cap,
                new DraftLoopRun.Basis("same-source", "完整依据", null), manuscript("原稿", 1));
        assertThat(run.claim()).isTrue(); assertThat(run.claim()).isFalse(); return run;
    }
    private DraftCheck report(String body) {
        return new DraftCheck("检查摘要", List.of(new DraftCheck.Issue("FLUENCY-1", QualityDimension.FLUENCY, "指代不清", body,
                "正文依据", "", "", "", "澄清指代")));
    }
    private DraftJudgment revision(String body) {
        return new DraftJudgment(DraftJudgment.Action.REVISED, List.of(new DraftJudgment.Decision("FLUENCY-1", DraftJudgment.Verdict.ACCEPT, "正文有据")),
                content(body), List.of("FLUENCY-1 澄清指代"));
    }

    @Test void stopsOnlyForRealOutcomesAndNeverClaimsUncheckedRevisionsPassed() {
        var run = run(10); run.checked(content("原稿"), new DraftCheck("未发现明确问题", List.of()));
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.B_CLEAR);
        assertThat(run.getRounds()).hasSize(1); assertThat(run.getRounds().getFirst().judgment()).isNull();
        assertThatThrownBy(() -> run.judged(revision("新稿"))).isInstanceOf(IllegalStateException.class);

        var rejected = run(10); rejected.checked(content("原稿"), report("原稿"));
        assertThat(rejected.judged(new DraftJudgment(DraftJudgment.Action.NO_CHANGE,
                List.of(new DraftJudgment.Decision("FLUENCY-1", DraftJudgment.Verdict.REJECT, "有限视角不是作者错误")), null, List.of()))).isFalse();
        assertThat(rejected.getStopReason()).isEqualTo(DraftLoopRun.StopReason.C_NO_CHANGE);
        assertThat(rejected.getRounds().getFirst().check().issues()).hasSize(1);

        var deferred = run(10); deferred.checked(content("原稿"), report("原稿"));
        assertThat(deferred.judged(new DraftJudgment(DraftJudgment.Action.NEEDS_CONTEXT,
                List.of(new DraftJudgment.Decision("FLUENCY-1", DraftJudgment.Verdict.DEFER, "需要变更人物既往经历")), null, List.of()))).isFalse();
        assertThat(deferred.getStopReason()).isEqualTo(DraftLoopRun.StopReason.NEEDS_CONTEXT);

        var unchanged = run(10); unchanged.checked(content("原稿"), report("原稿"));
        assertThat(unchanged.judged(revision("原稿"))).isFalse();
        assertThat(unchanged.getStopReason()).isEqualTo(DraftLoopRun.StopReason.NO_PROGRESS);
        assertThat(unchanged.getRounds().getFirst().afterManuscriptId()).isNull();

        var oscillating = run(10); oscillating.checked(content("原稿"), report("原稿"));
        assertThat(oscillating.judged(revision("新稿"))).isTrue(); oscillating.revised(manuscript("新稿", 2), "新稿");
        oscillating.checked(content("新稿"), report("新稿"));
        assertThat(oscillating.judged(revision("原稿"))).isFalse();
        assertThat(oscillating.getStopReason()).isEqualTo(DraftLoopRun.StopReason.CYCLE_DETECTED);

        var capped = run(10);
        for (int i = 1; i <= 10; i++) {
            String before = i == 1 ? "原稿" : "新稿" + (i - 1), after = "新稿" + i;
            capped.checked(content(before), report(before)); assertThat(capped.judged(revision(after))).isTrue();
            capped.revised(manuscript(after, i + 1), after);
        }
        assertThat(capped.getStopReason()).isEqualTo(DraftLoopRun.StopReason.ROUND_LIMIT);
        assertThat(capped.getRounds()).hasSize(10);
        assertThat(capped.getRounds().getLast().afterManuscriptId()).isEqualTo(capped.getManuscriptId());
        assertThat(capped.getStatus()).isEqualTo(DraftLoopRun.Status.STOPPED);
        assertThatThrownBy(() -> capped.checked(content("新稿10"), report("新稿10"))).isInstanceOf(IllegalStateException.class);
    }

    @Test void cancellationAndExplicitRetryKeepAccumulatedState() {
        var run = run(10); run.stop(DraftLoopRun.StopReason.CANCELLED); run.fail("迟到错误");
        assertThat(run.getStatus()).isEqualTo(DraftLoopRun.Status.CANCELLED);
        assertThat(run.getErrorMessage()).isNull();
        assertThatThrownBy(() -> run.checked(content("原稿"), report("原稿"))).isInstanceOf(IllegalStateException.class);

        var retried = run(3); retried.checked(content("原稿"), report("原稿"));
        var id = retried.getManuscriptId(); var basis = retried.getBasis();
        retried.fail("裁决格式错误"); retried.retry(); assertThat(retried.claim()).isTrue();
        assertThat(retried.getPhase()).isEqualTo(DraftLoopRun.Phase.C);
        assertThat(retried.getRounds()).hasSize(1); assertThat(retried.getManuscriptId()).isEqualTo(id);
        assertThat(retried.getBasis()).isSameAs(basis); assertThat(retried.getMaxRounds()).isEqualTo(3);
        assertThat(retried.getErrorMessage()).isNull(); retried.stop(DraftLoopRun.StopReason.CANCELLED);
        assertThatThrownBy(retried::retry).isInstanceOf(IllegalStateException.class);
    }

    @Test void validatesTransportAndRequiresRealModelLimitAndDraft() {
        var run = run(10);
        assertThatThrownBy(() -> run.checked(content("原稿"), report("不存在的引文"))).hasMessageContaining("证据");
        run.checked(content("原稿"), report("原稿"));
        var wrong = new DraftJudgment(DraftJudgment.Action.NO_CHANGE,
                List.of(new DraftJudgment.Decision("unknown", DraftJudgment.Verdict.REJECT, "原因")), null, List.of());
        assertThatThrownBy(() -> run.judged(wrong)).hasMessageContaining("不一致");
        assertThat(run.getPhase()).isEqualTo(DraftLoopRun.Phase.C);

        assertThatThrownBy(() -> DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.LOCAL_TEMPLATE, true, 10, null, null)).hasMessageContaining("真实模型");
        assertThatThrownBy(() -> run(11)).hasMessageContaining("10");
        assertThatThrownBy(() -> DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, false, 10, null, null)).hasMessageContaining("草稿");
        var accepted = manuscript("原稿", 1); accepted.accept();
        assertThatThrownBy(() -> DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, false, 10, null, accepted)).hasMessageContaining("草稿");
    }

    @Test void roundsAndDecisionsSurviveJsonRoundTrip() throws Exception {
        var run = run(10); run.checked(content("原稿"), report("原稿")); run.judged(revision("新稿"));
        var mapper = new ObjectMapper();
        var copy = mapper.readValue(mapper.writeValueAsString(run.getRounds().getFirst()), DraftLoopRun.Round.class);
        assertThat(copy).isEqualTo(run.getRounds().getFirst());
    }
}
