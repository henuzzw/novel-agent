package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.ModelFailureDetails;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.*;
import com.novelagent.writing.infrastructure.DraftLoopModel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DraftLoopProtocolTest {
    private final ObjectMapper json = new ObjectMapper();
    private final StructuredModelGateway gateway = mock(StructuredModelGateway.class);
    private final DraftLoopModel model = new DraftLoopModel(gateway, json);
    private final DraftLoopRun run = mock(DraftLoopRun.class);
    private final ManuscriptContent draft = new ManuscriptContent("纸条", "许宁收回本子。", "问清要求", List.of());
    private final DraftCheck check = new DraftCheck("指代问题", List.of(new DraftCheck.Issue(
            "B-FLUENCY-001", QualityDimension.FLUENCY, "文字来源不清", draft.body(), "已有补记动作", "", "", "", "明确自己的本子")));

    private void respond(Object output) throws Exception { respondRaw(json.writeValueAsString(output)); }
    private void respondRaw(String raw) {
        when(run.getId()).thenReturn(UUID.randomUUID()); when(run.getProjectId()).thenReturn(UUID.randomUUID());
        when(run.getPhase()).thenReturn(DraftLoopRun.Phase.C); when(run.getProvider()).thenReturn(ModelProvider.LOCAL_CODEX);
        when(run.getBasis()).thenReturn(new DraftLoopRun.Basis("frozen", "资料", null)); when(run.getRounds()).thenReturn(List.of());
        doAnswer(call -> {
            var protocol = new JsonSchemaOutputProtocol(call.getArgument(5, JsonNode.class));
            Consumer<String> save = call.getArgument(9); save.accept(protocol.decode(raw)); return null;
        }).when(gateway).requestJsonWithBoundaries(any(), anyString(), any(), anyString(), anyString(),
                any(), anyString(), anyInt(), any(), any(), anyString());
    }
    private DraftJudgment judgment(DraftJudgment.Action action, DraftJudgment.Verdict verdict, ManuscriptContent content) {
        return new DraftJudgment(action, List.of(new DraftJudgment.Decision("B-FLUENCY-001", verdict, "按已有依据裁决")),
                content, content == null ? List.of() : List.of("B-FLUENCY-001：明确位置"));
    }
    @Test void revisedJsonPassesActualSchemaDtoAndCoverageAndPreservesFullBody() throws Exception {
        String body = "我把要求写在自己的本子上。\n\n她说：\"知道了。\"";
        respond(judgment(DraftJudgment.Action.REVISED, DraftJudgment.Verdict.ACCEPT,
                new ManuscriptContent("纸条", body, "记下要求", List.of())));
        var saved = new AtomicReference<DraftJudgment>(); model.judge(run, draft, check, saved::set);
        assertThat(saved.get().content().body()).isEqualTo(body);
    }
    @ParameterizedTest @CsvSource({"NO_CHANGE,REJECT", "NEEDS_CONTEXT,DEFER"})
    void nonRevisionPreservesNullContent(String action, String verdict) throws Exception {
        respond(judgment(DraftJudgment.Action.valueOf(action), DraftJudgment.Verdict.valueOf(verdict), null));
        var saved = new AtomicReference<DraftJudgment>(); model.judge(run, draft, check, saved::set);
        assertThat(saved.get().content()).isNull(); assertThat(saved.get().changeSummary()).isEmpty();
    }
    @Test void wrongContentTypeAndMissingFieldsFailBeforeSavingWithoutLeakingBody() {
        for (String output : List.of("{\"action\":\"REVISED\",\"decisions\":[],\"content\":\"PRIVATE_BODY\",\"changeSummary\":[]}",
                "{\"action\":\"REVISED\",\"decisions\":[],\"content\":{},\"changeSummary\":[]}")) {
            respondRaw(output); Consumer<DraftJudgment> save = mock(Consumer.class);
            var failure = catchThrowableOfType(() -> model.judge(run, draft, check, save), IllegalArgumentException.class);
            assertThat(failure).hasMessageContaining("content").hasMessageNotContaining("PRIVATE_BODY");
            assertThat(ModelFailureDetails.from(failure).category()).isEqualTo("OUTPUT_VALIDATION"); verifyNoInteractions(save);
        }
    }
    private DraftCheck report(String first, String second) {
        return new DraftCheck("逻辑问题", List.of(new DraftCheck.Issue("B-LOGIC-001", QualityDimension.LOGIC, "表述不一致",
                List.of(new DraftCheck.Evidence(first), new DraftCheck.Evidence(second)), "正文两处交流", "", "", "", "澄清表述")));
    }
    @Test void separatedMultilineEvidencePassesButInventedOrLabelledQuotesStillFail() throws Exception {
        String first = "“去超市。”\n\n“不用买。”她说。", second = "“我在想买什么。”";
        var current = new ManuscriptContent("生日", first + "\n\n他回到教室。\n\n" + second, "摘要", List.of());
        respond(report(first, second)); var saved = new AtomicReference<DraftCheck>(); model.check(run, current, saved::set);
        assertThat(saved.get().issues().getFirst().evidence()).extracting(DraftCheck.Evidence::quote).containsExactly(first, second);
        for (String bad : List.of("PRIVATE_FABRICATED_QUOTE", "正文引文一：" + second)) {
            respond(report(first, bad)); Consumer<DraftCheck> save = mock(Consumer.class);
            var failure = catchThrowableOfType(() -> model.check(run, current, save), IllegalArgumentException.class);
            assertThat(failure).hasMessageContaining("B-LOGIC-001", "evidence[1]").hasMessageNotContaining(bad);
            verifyNoInteractions(save);
        }
    }
    @Test void validJsonDoesNotBypassDecisionCoverage() throws Exception {
        respond(new DraftJudgment(DraftJudgment.Action.NO_CHANGE,
                List.of(new DraftJudgment.Decision("OTHER", DraftJudgment.Verdict.REJECT, "理由")), null, List.of()));
        assertThatThrownBy(() -> model.judge(run, draft, check, ignored -> fail("must not save"))).hasMessageContaining("不一致");
    }
}
