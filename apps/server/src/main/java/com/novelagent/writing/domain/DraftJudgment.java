package com.novelagent.writing.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** C 的逐项裁决和可选新稿。只检查传输协议，不以代码判断文学质量。 */
public record DraftJudgment(Action action, List<Decision> decisions, ManuscriptContent content, List<String> changeSummary) {
    public enum Action { REVISED, NO_CHANGE, NEEDS_CONTEXT }
    public enum Verdict { ACCEPT, REJECT, DEFER }
    public record Decision(String issueId, Verdict verdict, String reason) { }

    public DraftJudgment {
        if (action == null || decisions == null || changeSummary == null) throw new IllegalArgumentException("裁决输出不完整");
        decisions = List.copyOf(decisions);
        changeSummary = List.copyOf(changeSummary);
        if (action == Action.REVISED) {
            requireContent(content);
            if (changeSummary.isEmpty() || decisions.stream().noneMatch(d -> d != null && d.verdict() == Verdict.ACCEPT)) {
                throw new IllegalArgumentException("修订必须关联接受的问题并说明实际修改");
            }
        } else if (content != null || !changeSummary.isEmpty()) {
            throw new IllegalArgumentException("未修订时不返回新正文或修改说明");
        }
    }

    public void requireCoverage(DraftCheck report) {
        var expected = new HashSet<String>();
        report.issues().forEach(i -> expected.add(i.id()));
        Set<String> actual = new HashSet<>();
        for (var decision : decisions) {
            if (decision == null || decision.issueId() == null || !actual.add(decision.issueId())
                    || decision.verdict() == null || decision.reason() == null || decision.reason().isBlank()) {
                throw new IllegalArgumentException("裁决必须逐项给出唯一编号、结论和原因");
            }
        }
        if (!actual.equals(expected)) throw new IllegalArgumentException("裁决问题与本轮检查不一致");
        if (action != Action.REVISED && decisions.stream().anyMatch(d -> d.verdict() == Verdict.ACCEPT)) {
            throw new IllegalArgumentException("接受修复的问题必须返回修订稿");
        }
        if (action == Action.NEEDS_CONTEXT && decisions.stream().noneMatch(d -> d.verdict() == Verdict.DEFER)) {
            throw new IllegalArgumentException("资料不足必须说明暂缓的问题");
        }
    }

    public static void requireContent(ManuscriptContent content) {
        if (content == null || content.title() == null || content.title().isBlank()
                || content.body() == null || content.body().isBlank() || content.summary() == null || content.summary().isBlank()
                || content.continuityNotes() == null) throw new IllegalArgumentException("模型缺少完整标题、正文或摘要");
    }
}
