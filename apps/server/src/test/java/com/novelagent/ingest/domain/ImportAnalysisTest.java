package com.novelagent.ingest.domain;

import static org.assertj.core.api.Assertions.*;
import com.novelagent.project.domain.CreativeBoundaries;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportAnalysisTest {
    private final UUID chapterId = UUID.randomUUID();
    private ImportAnalysis.Chapter chapter(String text) { return new ImportAnalysis.Chapter(chapterId, 1, "第一章", text); }
    private ImportAnalysis.Item item(String certainty, List<ImportAnalysis.Evidence> evidence) { return new ImportAnalysis.Item("note", "CLUE", certainty, "纸条", "纸条来源仍不明确", List.of("林安"), "UNRESOLVED", evidence); }
    @Test void slicesCoverEveryCharacterAndRefuseOversizedInputs() {
        String text = "x".repeat(15999) + "\uD83D\uDE00" + "y".repeat(20000);
        var slices = ImportAnalysis.slices(List.of(chapter(text)));
        assertThat(slices).hasSize(3); assertThat(slices.getFirst().start()).isZero(); assertThat(slices.getLast().end()).isEqualTo(text.length());
        for (int i = 1; i < slices.size(); i++) assertThat(slices.get(i).start()).isEqualTo(slices.get(i - 1).end());
        assertThat(slices.getFirst().end()).isEqualTo(15999);

        assertThatThrownBy(() -> ImportAnalysis.slices(List.of(chapter("x".repeat(16000 * 40 + 1))))).hasMessageContaining("40 段");
    }
    @Test void evidenceIsNormalizedToExactWholeChapterOccurrences() {
        var source = chapter("纸条。前段结束。纸条。后段。");
        var evidence = new ImportAnalysis.Evidence(chapterId, "纸条", 0);
        var batch = new ImportAnalysis.Batch("只分析后段", List.of(item("FACT", List.of(evidence))));
        int start = source.content().lastIndexOf("纸条");
        var normalized = ImportAnalysis.normalizeBatch(batch, new ImportAnalysis.Slice(chapterId, 1, "第一章", start, source.content().length()), List.of(source));
        assertThat(normalized.items().getFirst().evidence().getFirst().occurrence()).isEqualTo(1);
        assertThat(ImportAnalysis.locate(normalized.items().getFirst().evidence().getFirst(), source.content())).isEqualTo(start);

        var source2 = chapter("前段。纸条在书里。");
        var batch2 = new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "纸条在书里。", 23)))));
        var normalized2 = ImportAnalysis.normalizeBatch(batch2, ImportAnalysis.slices(List.of(source2)).getFirst(), List.of(source2));
        assertThat(normalized2.items().getFirst().evidence().getFirst().occurrence()).isZero();

        String first = "我只是想找个安静又自在的位置，她却把我的离开理解成了厌烦。";
        String second = "我心想：我犯得着吗？于是索性不再去那个角落。";
        var source3 = chapter("前文。\n\n*" + first + "*\n\n" + second + "\n后文。");
        var quote = new ImportAnalysis.Evidence(chapterId, first + "\n\n" + second, 0);
        var normalized3 = ImportAnalysis.normalizeBatch(new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(quote)))),
                ImportAnalysis.slices(List.of(source3)).getFirst(), List.of(source3));
        var actual = normalized3.items().getFirst().evidence().getFirst();
        assertThat(actual.quote()).isEqualTo(first + "*\n\n" + second);
        assertThat(source3.content()).contains(actual.quote());
        assertThat(normalized3.items().getFirst().description()).isEqualTo("纸条来源仍不明确");

        var source4 = chapter("前文\r\n**现实校园。**\r\n\r\n没有架空设定。\r\n");
        var quote2 = new ImportAnalysis.Evidence(chapterId, "现实校园。\n\n没有架空设定。", 42);
        var assessment = new ImportAnalysis.WorldAssessment(CreativeBoundaries.WorldMode.REALISTIC,
                "依据校园场景", List.of(quote2));
        int start2 = source4.content().indexOf("**");
        var normalized4 = ImportAnalysis.normalizeBatch(new ImportAnalysis.Batch("摘要", List.of(), assessment),
                new ImportAnalysis.Slice(chapterId, 1, "第一章", start2, source4.content().length()), List.of(source4));
        var actual2 = normalized4.worldAssessment().evidence().getFirst();
        assertThat(actual2.quote()).isEqualTo("现实校园。**\r\n\r\n没有架空设定。");
        assertThat(ImportAnalysis.locate(actual2, source4.content())).isEqualTo(start2 + 2);
        assertThat(actual2.occurrence()).isZero();
    }
    @Test void inventedAmbiguousOrOutOfSliceEvidenceIsRejected() {
        var source = chapter("纸条在书里。");
        assertThatThrownBy(() -> ImportAnalysis.validateBatch(new ImportAnalysis.Batch("说明", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "纸条不在书里", 0))))), ImportAnalysis.slices(List.of(source)), List.of(source))).hasMessageContaining("证据不存在");
        assertThatThrownBy(() -> item("INFERENCE", List.of())).hasMessageContaining("必须附原文依据");
        assertThat(item("UNKNOWN", List.of()).certainty()).isEqualTo("UNKNOWN");

        var source2 = chapter("纸条。纸条。");
        var batch = new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "纸条", 23)))));
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(batch, ImportAnalysis.slices(List.of(source2)).getFirst(), List.of(source2)))
                .hasMessageContaining("items[0].evidence[0]").hasMessageContaining("序号不匹配");
        var fake = new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(new ImportAnalysis.Evidence(chapterId, "烧掉纸条", 23)))));
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(fake, ImportAnalysis.slices(List.of(source2)).getFirst(), List.of(source2)))
                .hasMessageContaining("items[0].evidence[0]").hasMessageContaining("证据不存在");

        var source3 = chapter("*纸条。*\n\n后文。\n\n_纸条。_\n\n后文。");
        var evidence = new ImportAnalysis.Evidence(chapterId, "纸条。\n\n后文。", 1);
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(evidence)))),
                ImportAnalysis.slices(List.of(source3)).getFirst(), List.of(source3)))
                .hasMessageContaining("items[0].evidence[0]").hasMessageContaining("多处原文");

        for (String text : List.of("```text\n*纸条。*\n\n后文。\n```", "纸条。*\n\n后文。", "*纸条。*\n\n不能遗漏。\n\n后文。")) {
            var source4 = chapter(text);
            var evidence2 = new ImportAnalysis.Evidence(chapterId, "纸条。\n\n后文。", 0);
            assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(evidence2)))),
                    ImportAnalysis.slices(List.of(source4)).getFirst(), List.of(source4)))
                    .hasMessageContaining("证据不存在");
        }

        var source5 = chapter("*纸条。*\n\n后文。");
        var evidence3 = new ImportAnalysis.Evidence(chapterId, "纸条。\n\n后文。", 0);
        assertThatThrownBy(() -> ImportAnalysis.normalizeBatch(new ImportAnalysis.Batch("摘要", List.of(item("FACT", List.of(evidence3)))),
                new ImportAnalysis.Slice(chapterId, 1, "第一章", 0, 6), List.of(source5)))
                .hasMessageContaining("证据不存在");
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
