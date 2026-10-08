package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.novelagent.agent.infrastructure.AutomationRunRepository;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.writing.domain.*;
import com.novelagent.writing.infrastructure.*;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class DraftLoopStoreTest {
    private final UUID project = UUID.randomUUID();
    private final DraftLoopRunRepository runs = mock(DraftLoopRunRepository.class);
    private final ManuscriptVersionRepository manuscripts = mock(ManuscriptVersionRepository.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final DraftLoopContext contexts = mock(DraftLoopContext.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final DraftLoopStore store = new DraftLoopStore(runs, manuscripts, access, contexts, names,
            mock(EntityManager.class), mock(DraftLoopRecovery.class), mock(AutomationRunRepository.class));
    private final ManuscriptContent before = new ManuscriptContent("标题", "她递还纸条。", "摘要", List.of());
    private final ManuscriptVersion source = ManuscriptVersion.create(UUID.randomUUID(), project, null, 1, 1,
            "DEEPSEEK", null, null, before, List.of());
    private DraftLoopRun run;

    @BeforeEach void setup() {
        var basis = new DraftLoopRun.Basis("basis", "冻结资料",
                new ManuscriptBasis(UUID.randomUUID(), "fingerprint", mock(ChapterContractContent.class)));
        run = DraftLoopRun.create(project, UUID.randomUUID(), 1, ModelProvider.DEEPSEEK, false, 1, basis, source);
        run.claim();
        when(access.requireOwnedProject(project)).thenReturn(mock(NovelProject.class));
        when(runs.findByIdAndProjectId(run.getId(), project)).thenReturn(Optional.of(run));
        when(contexts.capture(project, 1)).thenReturn(new DraftLoopContext.Source(basis, null, source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(project, 1)).thenReturn(Optional.of(source));
        when(names.tokenize(eq(project), any(ManuscriptContent.class))).thenAnswer(call -> call.getArgument(1));
        when(names.render(eq(project), any(ManuscriptContent.class))).thenAnswer(call -> call.getArgument(1));
        when(manuscripts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    private DraftCheck report() { return new DraftCheck("指代问题", List.of(new DraftCheck.Issue("F1", QualityDimension.FLUENCY,
            "主语不明", before.body(), "视角人物", "", "", "", "明确主体"))); }
    private DraftJudgment judgment() { return new DraftJudgment(DraftJudgment.Action.REVISED,
            List.of(new DraftJudgment.Decision("F1", DraftJudgment.Verdict.ACCEPT, "计划内表达")),
            new ManuscriptContent("标题", "沈秋递还纸条。", "更新摘要", List.of()), List.of("F1 明确主体")); }

    @Test void cancelledTaskDiscardsLateModelOutputWithoutSavingOrRecapturing() {
        store.checked(project, run.getId(), before, report()); store.cancel(project, run.getId());
        clearInvocations(contexts, manuscripts);
        store.judged(project, run.getId(), judgment());
        assertThat(run.getStatus()).isEqualTo(DraftLoopRun.Status.CANCELLED);
        verifyNoInteractions(contexts, manuscripts);
    }
    @Test void changedBasisStopsBeforeApplyingB() {
        when(contexts.capture(project, 1)).thenReturn(new DraftLoopContext.Source(new DraftLoopRun.Basis("new-basis", "新风格", null), null, source));
        store.checked(project, run.getId(), before, report());
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.SOURCE_CHANGED);
        assertThat(run.getRounds()).isEmpty(); verify(manuscripts, never()).saveAndFlush(any());
    }
    @Test void inPlaceAuthorEditOrAcceptanceStopsBeforeApplyingC() {
        store.checked(project, run.getId(), before, report());
        ReflectionTestUtils.setField(source, "rowVersion", 1L);
        store.judged(project, run.getId(), judgment());
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.SOURCE_CHANGED);
        verify(manuscripts, never()).saveAndFlush(any());
    }
    @Test void acceptedDraftIsNotAutomaticallyEdited() {
        store.checked(project, run.getId(), before, report()); source.accept();
        store.judged(project, run.getId(), judgment());
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.SOURCE_CHANGED);
        verify(manuscripts, never()).saveAndFlush(any());
    }
    @Test void validRevisionCreatesDraftKeepsBaselineAndMarksCapUnverified() {
        store.checked(project, run.getId(), before, report()); store.judged(project, run.getId(), judgment());
        var saved = org.mockito.ArgumentCaptor.forClass(ManuscriptVersion.class);
        verify(manuscripts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ManuscriptStatus.DRAFT);
        assertThat(saved.getValue().getBaseManuscriptVersionId()).isEqualTo(source.getId());
        assertThat(saved.getValue().getContent().summary()).isEqualTo("更新摘要");
        assertThat(run.getStopReason()).isEqualTo(DraftLoopRun.StopReason.ROUND_LIMIT);
        assertThat(source.getContent()).isEqualTo(before);
    }
    @Test void ownershipIsRequiredBeforeReadingTaskDetails() {
        when(access.requireOwnedProject(project)).thenThrow(new IllegalArgumentException("项目不可见"));
        assertThatThrownBy(() -> store.get(project, run.getId())).hasMessageContaining("不可见");
        verify(runs, never()).findByIdAndProjectId(any(), any());
    }
}
