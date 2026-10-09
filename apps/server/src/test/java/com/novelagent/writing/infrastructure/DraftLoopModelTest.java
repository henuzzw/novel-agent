package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptCatalog;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.writing.domain.*;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DraftLoopModelTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StructuredModelGateway gateway = mock(StructuredModelGateway.class);
    private final DraftLoopModel model = new DraftLoopModel(gateway, mapper);
    private final ManuscriptContent draft = new ManuscriptContent("标题", "她把纸条递回去。", "摘要", List.of());
    private DraftLoopRun run() {
        var source = ManuscriptVersion.create(UUID.randomUUID(), UUID.randomUUID(), null, 1, 1, "DEEPSEEK", null, null, draft, List.of());
        var run = DraftLoopRun.create(source.getProjectId(), UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, false, 10,
                new DraftLoopRun.Basis("fingerprint", "圣经：人物秘密不代表已知；选定风格：市井幽默；当前计划与前文事实", null), source);
        run.claim(); return run;
    }
    private void returns(String json) {
        doAnswer(call -> { Consumer<String> consume = call.getArgument(9); consume.accept(json); return json; })
                .when(gateway).request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any());
    }

    @Test void bAndCReceiveSameIndependentFrozenBasisWithNoAuthorInstruction() throws Exception {
        var run = run(); var report = new DraftCheck("未发现问题", List.of());
        returns(mapper.writeValueAsString(report)); model.check(run, draft, ignored -> { });
        returns("{\"action\":\"NO_CHANGE\",\"decisions\":[],\"content\":null,\"changeSummary\":[]}");
        model.judge(run, draft, report, ignored -> { });
        var prompts = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(2)).request(eq(run.getProjectId()), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), prompts.capture(),
                any(), anyString(), anyInt(), eq(CodexSessionPolicy.NEW_THREAD), any());
        for (var prompt : prompts.getAllValues()) {
            var data = mapper.readTree(prompt);
            assertThat(data.path("independentWritingBasis").asText()).isEqualTo(run.getBasis().prompt());
            assertThat(data.path("currentDraft").path("body").asText()).isEqualTo(draft.body());
            assertThat(data.has("authorInstruction")).isFalse();
            assertThat(data.path("frozenSourceFingerprint").asText()).isEqualTo("fingerprint");
        }
        assertThat(mapper.readTree(prompts.getAllValues().getFirst()).has("suggestionsNotFacts")).isFalse();
        assertThat(mapper.readTree(prompts.getAllValues().getLast()).has("suggestionsNotFacts")).isTrue();
    }

    @Test void invalidEvidenceIsRejectedInsideRecordedOutputBeforeSaving() {
        returns("{\"summary\":\"问题\",\"issues\":[{\"id\":\"L1\",\"category\":\"LOGIC\",\"description\":\"描述\",\"evidence\":\"凭空编的原文\",\"existingBasis\":\"\",\"gap\":\"\",\"candidateDesign\":\"\",\"impact\":\"\",\"suggestion\":\"修改\"}]}");
        var saved = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(() -> model.check(run(), draft, ignored -> saved.set(true))).hasMessageContaining("证据");
        assertThat(saved).isFalse();
    }

    @Test void checkProtocolDoesNotRequireScores() {
        assertThat(model.checkSchema().path("properties").has("scores")).isFalse();
    }

    @Test void cRejectsUnexpectedOutputAndDoesNotSaveIt() {
        returns("{\"action\":\"NO_CHANGE\",\"decisions\":[],\"content\":null,\"changeSummary\":[],\"publishCanon\":true}");
        assertThatThrownBy(() -> model.judge(run(), draft, new DraftCheck("无问题", List.of()), ignored -> fail("must not save")))
                .hasMessageContaining("输出协议");
    }
}
