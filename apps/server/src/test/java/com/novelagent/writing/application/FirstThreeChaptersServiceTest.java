package com.novelagent.writing.application;

import static com.novelagent.writing.FirstThreeChaptersFixtures.PROJECT;
import static com.novelagent.writing.FirstThreeChaptersFixtures.budget;
import static com.novelagent.writing.FirstThreeChaptersFixtures.source;
import static com.novelagent.writing.FirstThreeChaptersFixtures.unassessed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersReport;
import com.novelagent.writing.domain.FirstThreeChaptersSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FirstThreeChaptersServiceTest {
    private final FirstThreeChaptersStore store = mock(FirstThreeChaptersStore.class);
    private final FirstThreeChaptersModel model = mock(FirstThreeChaptersModel.class);
    private final FirstThreeChaptersService service = new FirstThreeChaptersService(store, model);
    private final FirstThreeChaptersSource source = source();
    @BeforeEach void setup() {
        when(store.snapshot(any(), any())).thenReturn(source);
        when(store.latest(any(), any())).thenReturn(List.of());
        when(model.budget(any(), any(), any())).thenReturn(budget(true));
        when(model.check(any(), any(), any())).thenReturn(unassessed());
        when(store.save(any(), any(), any(), any(), any(), any())).thenAnswer(a -> FirstThreeChaptersReport.create(
                UUID.randomUUID(), 1, a.getArgument(2), a.getArgument(3), a.getArgument(0), a.getArgument(4), a.getArgument(5)));
    }
    @Test void getReadsFullChaptersAndBudgetWithoutChecking() {
        var view = service.get(PROJECT, List.of(), ModelProvider.DEEPSEEK, "");
        assertThat(view.available()).isTrue();
        assertThat(view.source().chapters().getLast().body()).endsWith("Ending 3.");
        assertThat(view.latestValidReport()).isNull();
        verify(model, never()).check(any(), any(), any());
        verify(store, never()).save(any(), any(), any(), any(), any(), any());
    }
    @Test void explicitCheckUsesOneCallAndSavesOnlyAfterValidation() {
        var result = service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "查因果", source.fingerprint(), 13000);
        assertThat(result.current()).isTrue();
        verify(model, times(1)).check(source, ModelProvider.DEEPSEEK, "查因果");
        verify(store).save(eq(source), eq(List.of()), eq("DEEPSEEK"), eq("查因果"), any(), any());
    }
    @Test void lowerAuthorBudgetOrContextOverflowRejectsBeforeCall() {
        assertThatThrownBy(() -> service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "", source.fingerprint(), 11999))
                .hasMessageContaining("预算不足");
        when(model.budget(any(), any(), any())).thenReturn(budget(false));
        assertThatThrownBy(() -> service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "", source.fingerprint(), 99999))
                .hasMessageContaining("预算不足");
        verify(model, never()).check(any(), any(), any());
    }
    @Test void missingBodyCannotBeReplacedWithContract() {
        when(store.snapshot(any(), any())).thenReturn(new FirstThreeChaptersSource(PROJECT, source.outlineId(), 4,
                source.bibleId(), 5, 6, source.strategy(), "outline", "bible", "style", "profile",
                source.chapters().subList(0, 2), List.of("缺少第三章正文"), source.fingerprint()));
        assertThatThrownBy(() -> service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "", source.fingerprint(), 13000))
                .hasMessageContaining("缺少第三章正文");
        verify(model, never()).check(any(), any(), any());
    }
    @Test void changedBudgetSourceIsRejectedBeforeCall() {
        assertThatThrownBy(() -> service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "", "old", 13000))
                .hasMessageContaining("来源已变化");
        verify(model, never()).check(any(), any(), any());
    }
    @Test void fabricatedOrWrongChapterEvidenceCannotBeSaved() {
        var bad = new FirstThreeChaptersContent("范围", unassessed().assessments(), List.of(
                new FirstThreeChaptersContent.Issue("i1", FirstThreeChaptersContent.Dimension.LOGIC, "问题", "核对",
                        List.of(new FirstThreeChaptersContent.Evidence(2, "Ending 1.")))));
        when(model.check(any(), any(), any())).thenReturn(bad);
        assertThatThrownBy(() -> service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "", source.fingerprint(), 13000))
                .hasMessageContaining("连续原文");
        verify(store, never()).save(any(), any(), any(), any(), any(), any());
    }
    @Test void latestStaleAndEarlierValidAreDistinguished() {
        var stale = new FirstThreeChaptersSource(PROJECT, source.outlineId(), 4, source.bibleId(), 5, 6,
                "old", "outline", "bible", "style", "profile", source.chapters(), List.of(), "b".repeat(64));
        var old = FirstThreeChaptersReport.create(UUID.randomUUID(), 2, "DEEPSEEK", "", stale, unassessed(), budget(true));
        var valid = FirstThreeChaptersReport.create(UUID.randomUUID(), 1, "DEEPSEEK", "", source, unassessed(), budget(true));
        when(store.latest(any(), any())).thenReturn(List.of(old, valid));
        var view = service.get(PROJECT, List.of(), ModelProvider.DEEPSEEK, "");
        assertThat(view.latestReport().current()).isFalse();
        assertThat(view.latestValidReport().id()).isEqualTo(valid.getId());
    }
    @Test void sourceChangedDuringModelCallDoesNotReturnSuccessfulReport() {
        org.mockito.Mockito.doThrow(new IllegalStateException("检查期间正文已变化"))
                .when(store).save(any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.check(PROJECT, List.of(), ModelProvider.DEEPSEEK, "", source.fingerprint(), 13000))
                .hasMessageContaining("检查期间");
        verify(model, times(1)).check(any(), any(), any());
    }
    @Test void explicitSelectionMustHaveThreeDifferentIdsAndExplicitProvider() {
        assertThatThrownBy(() -> service.get(PROJECT, List.of(UUID.randomUUID()), ModelProvider.DEEPSEEK, ""))
                .hasMessageContaining("三个不同正文版本");
        assertThatThrownBy(() -> service.get(PROJECT, List.of(), null, "")).hasMessageContaining("明确选择");
    }
}
