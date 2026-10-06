package com.novelagent.ingest.domain;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportAnalysisTest {
    private final UUID chapterId = UUID.randomUUID();
    private ImportAnalysis.Chapter chapter(String text) { return new ImportAnalysis.Chapter(chapterId, 1, "第一章", text); }
    private ImportAnalysis.Item item(String certainty, List<ImportAnalysis.Evidence> evidence) { return new ImportAnalysis.Item("note", "CLUE", certainty, "纸条", "纸条来源仍不明确", List.of("林安"), "UNRESOLVED", evidence); }
    @Test void slicesCoverEveryCharacterWithoutCuttingSurrogatePairs() {
        String text = "x".repeat(15999) + "\uD83D\uDE00" + "y".repeat(20000);
        var slices = ImportAnalysis.slices(List.of(chapter(text)));
        assertThat(slices).hasSize(3); assertThat(slices.getFirst().start()).isZero(); assertThat(slices.getLast().end()).isEqualTo(text.length());
        for (int i = 1; i < slices.size(); i++) assertThat(slices.get(i).start()).isEqualTo(slices.get(i - 1).end());
        assertThat(slices.getFirst().end()).isEqualTo(15999);
    }
    @Test void refusesOversizedInputsInsteadOfClaimingPartialCoverage() {
        assertThatThrownBy(() -> ImportAnalysis.slices(List.of(chapter("x".repeat(16000 * 40 + 1))))).hasMessageContaining("40 段");
    }
    @Test void normalizesRepeatedEvidenceToItsWholeChapterOccurrence() {
        var source = chapter("纸条。前段结束。纸条。后段。");
        var evidence = new ImportAnalysis.Evidence(chapterId, "纸条", 0);
        var batch = new ImportAnalysis.Batch("只分析后段", List.of(item("FACT", List.of(evidence))));
        int start = source.content().lastIndexOf("纸条");
        var normalized = ImportAnalysis.normalizeBatch(batch, new ImportAnalysis.Slice(chapterId, 1, "第一章", start, source.content().length()), List.of(source));
        assertThat(normalized.items().getFirst().evidence().getFirst().occurrence()).isEqualTo(1);
        assertThat(ImportAnalysis.locate(normalized.items().getFirst().evidence().getFirst(), source.content())).isEqualTo(start);
    }
    @Test void rejectsFakeEvidenceAndWrongChapters() {
        var source = chapter("纸条在书里。");
        assertThatThrownBy(() -> ImportAnalysis.validateBatch(new ImportAnalysis.Batch("说明", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "纸条不在书里", 0))))), ImportAnalysis.slices(List.of(source)), List.of(source))).hasMessageContaining("证据不存在");
        assertThatThrownBy(() -> item("INFERENCE", List.of())).hasMessageContaining("必须附原文依据");
        assertThat(item("UNKNOWN", List.of()).certainty()).isEqualTo("UNKNOWN");
    }
    @Test void correctsIncorrectOrdinalsOnlyForUniqueLiteralQuotes() {
        var source = chapter("前段。纸条在书里。");
        var batch = new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "纸条在书里。", 23)))));
        var normalized = ImportAnalysis.normalizeBatch(batch, ImportAnalysis.slices(List.of(source)).getFirst(), List.of(source));
        assertThat(normalized.items().getFirst().evidence().getFirst().occurrence()).isZero();
    }
    @Test void doesNotGuessRepeatedQuotesOrRepairInventedText() {
        var source = chapter("纸条。纸条。");
        var batch = new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "纸条", 23)))));
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(batch, ImportAnalysis.slices(List.of(source)).getFirst(), List.of(source)))
                .hasMessageContaining("items[0].evidence[0]").hasMessageContaining("序号不匹配");
        var fake = new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "烧掉纸条", 23)))));
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(fake, ImportAnalysis.slices(List.of(source)).getFirst(), List.of(source)))
                .hasMessageContaining("items[0].evidence[0]").hasMessageContaining("证据不存在");
    }
    @Test void requiresCompleteDecisionsAndExplicitReworkInstructions() {
        var content = new ImportAnalysis.Content(List.of(), List.of(item("UNKNOWN", List.of())));
        assertThatThrownBy(() -> ImportAnalysis.validateDecisions(content, List.of(), ImportPlanningMode.ADAPT_SOURCE)).hasMessageContaining("逐项");
        assertThatThrownBy(() -> new ImportAnalysis.Decision("note", "REWORK", " ")).hasMessageContaining("重构项");
        assertThatThrownBy(() -> ImportAnalysis.validateDecisions(content, List.of(new ImportAnalysis.Decision("note", "REWORK", "改成信件")), ImportPlanningMode.CONTINUE_MANUSCRIPT)).hasMessageContaining("续写不能重构");
        ImportAnalysis.validateDecisions(content, List.of(new ImportAnalysis.Decision("note", "DROP", "模型判断缺乏依据")), ImportPlanningMode.CONTINUE_MANUSCRIPT);
    }
    @Test void eachBatchKeepsSeparateStableKeysWithoutGuessingSemanticMerges() {
        var batch = new ImportAnalysis.Batch("摘要", List.of(item("UNKNOWN", List.of())));
        var content = new ImportAnalysis.Content(List.of(), List.of()).append(batch, 0).append(batch, 1);
        assertThat(content.items()).extracting(ImportAnalysis.Item::key).containsExactly("b0_note", "b1_note");
    }
}
