package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.novelagent.agent.application.GenerationControlRegistry;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.*;
import com.novelagent.writing.infrastructure.DraftLoopModel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DraftLoopServiceTest {
    private final UUID project = UUID.randomUUID();
    private final DraftLoopStore store = mock(DraftLoopStore.class);
    private final DraftLoopContext contexts = mock(DraftLoopContext.class);
    private final DraftLoopModel model = mock(DraftLoopModel.class);
    private final GenerationControlRegistry controls = new GenerationControlRegistry();
    private final DraftLoopService service = new DraftLoopService(store, contexts, model, controls, Runnable::run);
    private final ManuscriptContent original = new ManuscriptContent("标题", "旧稿", "摘要", List.of());
    private final ManuscriptContent revised = new ManuscriptContent("标题", "新稿", "更新摘要", List.of());
    private final AtomicReference<ManuscriptContent> body = new AtomicReference<>(original);
    private DraftLoopRun run;

    @BeforeEach void setup() {
        run = DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, true, 10,
                new DraftLoopRun.Basis("snapshot", "圣经、人物、风格、前文", null), null);
        when(store.get(project, run.getId())).thenReturn(run);
        when(store.ready(project, run.getId())).thenAnswer(call -> run.active());
        when(store.body(run)).thenAnswer(call -> body.get());
        doAnswer(call -> {
            GeneratedManuscript output = call.getArgument(2); body.set(output.content());
            run.wrote(manuscript(output.content(), 1), output.content().body()); return null;
        }).when(store).wrote(eq(project), eq(run.getId()), any());
        doAnswer(call -> { run.checked(call.getArgument(2), call.getArgument(3)); return null; })
                .when(store).checked(eq(project), eq(run.getId()), any(), any());
        doAnswer(call -> {
            DraftJudgment judgment = call.getArgument(2);
            if (run.judged(judgment)) { body.set(judgment.content()); run.revised(manuscript(judgment.content(), 2), judgment.content().body()); }
            return null;
        }).when(store).judged(eq(project), eq(run.getId()), any());
        doAnswer(call -> { run.fail(call.getArgument(2)); return null; }).when(store).fail(eq(project), eq(run.getId()), any());
        when(store.cancel(project, run.getId())).thenAnswer(call -> { run.stop(DraftLoopRun.StopReason.CANCELLED); return run; });
        run.claim();
    }

    private ManuscriptVersion manuscript(ManuscriptContent content, int number) {
        return ManuscriptVersion.create(UUID.randomUUID(), project, null, 1, number, "DEEPSEEK", null, null, content, List.of());
    }

    private DraftCheck issues() { return new DraftCheck("语句问题", List.of(new DraftCheck.Issue("F1", QualityDimension.FLUENCY,
            "不清楚", "旧稿", "原文依据", "", "", "", "澄清"))); }

    @Test void serialWriteCheckJudgeRecheckStopsWithoutManualApprovalOrCanon() {
        doAnswer(call -> { Consumer<GeneratedManuscript> save = call.getArgument(1); save.accept(new GeneratedManuscript(original)); return null; })
                .when(model).write(eq(run), any());
        doAnswer(call -> { Consumer<DraftCheck> save = call.getArgument(2);
            save.accept(body.get().equals(original) ? issues() : new DraftCheck("未发现明确问题", List.of())); return null; })
                .when(model).check(eq(run), any(), any());
        doAnswer(call -> { Consumer<DraftJudgment> save = call.getArgument(3);
            save.accept(new DraftJudgment(DraftJudgment.Action.REVISED, List.of(new DraftJudgment.Decision("F1", DraftJudgment.Verdict.ACCEPT, "有依据")),
                    revised, List.of("F1 澄清"))); return null; }).when(model).judge(eq(run), any(), any(), any());
        service.execute(project, run.getId());
        var order = inOrder(model);
        order.verify(model).write(eq(run), any()); order.verify(model).check(eq(run), eq(original), any());
        order.verify(model).judge(eq(run), eq(original), any(), any()); order.verify(model).check(eq(run), eq(revised), any());
        order.verifyNoMoreInteractions();
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.B_CLEAR);
        assertThat(run.getRounds()).hasSize(2);
        verify(store, never()).fail(any(), any(), any());
    }

    @Test void cancellationDuringARejectsContinuationEvenIfProviderReturns() {
        doAnswer(call -> { service.cancel(project, run.getId()); return null; }).when(model).write(eq(run), any());
        service.execute(project, run.getId());
        assertThat(run.getStatus()).isEqualTo(DraftLoopRun.Status.CANCELLED);
        verify(model, never()).check(any(), any(), any()); verify(store, never()).wrote(any(), any(), any());
    }

    @Test void modelFailureDoesNotRetryAndKeepsPreviouslySavedDraft() {
        doAnswer(call -> { Consumer<GeneratedManuscript> save = call.getArgument(1); save.accept(new GeneratedManuscript(original)); return null; })
                .when(model).write(eq(run), any());
        doThrow(new IllegalStateException("响应超时，供应商未返回完整正文")).when(model).check(eq(run), any(), any());
        service.execute(project, run.getId());
        assertThat(run.getStatus()).isEqualTo(DraftLoopRun.Status.FAILED);
        assertThat(run.getManuscriptId()).isNotNull(); assertThat(run.getErrorMessage()).contains("响应超时");
        verify(model, times(1)).check(any(), any(), any()); verify(model, never()).judge(any(), any(), any(), any());
    }

    @Test void sourceGuardPreventsAnyModelCall() {
        when(store.ready(project, run.getId())).thenReturn(false); service.execute(project, run.getId());
        verifyNoInteractions(model);
    }
    @Test void duplicateCreateDoesNotRecollectContextOrDispatchAnotherWorker() {
        when(store.existing(project, run.getRequestKey(), 1, ModelProvider.DEEPSEEK, true, 10)).thenReturn(java.util.Optional.of(run));
        assertThat(service.create(project, run.getRequestKey(), 1, ModelProvider.DEEPSEEK, true, 10)).isSameAs(run);
        verifyNoInteractions(contexts, model);
        verify(store, never()).create(any(), any(), anyInt(), any(), anyBoolean(), anyInt(), any());
    }
}
