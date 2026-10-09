package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneOutlineTest {
    private static final String SCENES = "教室：先停止问题版本传阅。\n对照原稿后发现漏项，决定逐页返工。";

    @Test
    void roundTripKeepsFreeTextAndServerStatus() throws Exception {
        var mapper = new ObjectMapper();
        var original = outline(chapter(1, "完成返工"));
        assertThat(mapper.readValue(mapper.writeValueAsString(original), OutlineContent.class)).isEqualTo(original);
        assertThat(original.arcs().getFirst().chapters().getFirst().sceneOutline()).isEqualTo(SCENES);
    }

    @Test
    void emptyDraftIsMissingButArbitraryProseIsAccepted() {
        assertThat(new ChapterPlan(1, "标题", "江澈", "目标", "事件", "信息", "后果", 2000, 3000)
                .sceneOutlineNeedsUpdate()).isTrue();
        assertThat(chapter(1, "目标").withSceneOutline("一场完整互动，不按模板拆分。").sceneOutlineNeedsUpdate()).isFalse();
        assertThat(chapter(1, "目标").withSceneOutline("  ").sceneOutlineNeedsUpdate()).isTrue();
    }

    @Test
    void changedChapterKeepsOldTextAndMarksOnlyItsScenes() {
        var previous = outline(chapter(1, "完成返工"), chapter(2, "等待回应"));
        var next = outline(chapter(1, "先收回资料"), chapter(2, "等待回应")).reviewScenesAgainst(previous, false);
        assertThat(next.arcs().getFirst().chapters()).extracting(ChapterPlan::sceneOutlineNeedsUpdate)
                .containsExactly(true, false);
        assertThat(next.arcs().getFirst().chapters().getFirst().sceneOutline()).isEqualTo(SCENES);
    }

    @Test
    void revisedScenesClearStaleStatusButSavingUnchangedTextDoesNot() {
        var original = chapter(1, "完成返工");
        var stale = chapter(1, "先收回资料").reviewScenesAgainst(original, false);
        assertThat(stale.reviewScenesAgainst(stale, false).sceneOutlineNeedsUpdate()).isTrue();
        assertThat(stale.withSceneOutline("先收回所有问题版本，再安排校对。")
                .reviewScenesAgainst(stale, false).sceneOutlineNeedsUpdate()).isFalse();
    }

    @Test
    void changedBibleOrBookBasisMarksReusedScenes() {
        var old = outline(chapter(1, "完成返工"));
        assertThat(old.reviewScenesAgainst(old, true).arcs().getFirst().chapters().getFirst()
                .sceneOutlineNeedsUpdate()).isTrue();
        var changed = new OutlineContent(old.title(), "改变后的全书前提", old.structureSummary(), old.pacingStrategy(),
                old.suggestedMinWords(), old.suggestedMaxWords(), old.arcs());
        assertThat(changed.reviewScenesAgainst(old, false).arcs().getFirst().chapters().getFirst()
                .sceneOutlineNeedsUpdate()).isTrue();
    }

    @Test
    void changedArcOnlyMarksItsReusedScenes() {
        var first = outline(chapter(1, "完成返工")).arcs().getFirst();
        var second = new OutlineArc(2, "第二幕", "新目标", "冲突", "转折", "结果", 2000, 3000,
                List.of(chapter(2, "等待回应")));
        var old = new OutlineContent("书名", "前提", "结构", "节奏", 4000, 6000, List.of(first, second));
        var changed = new OutlineArc(1, first.title(), "先收回版本", first.mainConflict(), first.turningPoint(),
                first.outcome(), 2000, 3000, first.chapters());
        var next = new OutlineContent("书名", "前提", "结构", "节奏", 4000, 6000, List.of(changed, second))
                .reviewScenesAgainst(old, false);
        assertThat(next.arcs().stream().flatMap(arc -> arc.chapters().stream()))
                .extracting(ChapterPlan::sceneOutlineNeedsUpdate).containsExactly(true, false);
    }

    @Test
    void importStatusNormalizationDoesNotDropScenes() {
        var changed = chapter(1, "完成返工").withStatus(ChapterPlanStatus.OCCURRED);
        assertThat(changed.sceneOutline()).isEqualTo(SCENES);
        assertThat(changed.sceneOutlineNeedsUpdate()).isFalse();
    }

    private static ChapterPlan chapter(int number, String objective) {
        return new ChapterPlan(number, "返工", "江澈", objective, "发现漏项", "资料缺少条件", "未收回版本", 2000, 3000)
                .withSceneOutline(SCENES);
    }

    private static OutlineContent outline(ChapterPlan... chapters) {
        return new OutlineContent("书名", "前提", "结构", "节奏", 4000, 6000,
                List.of(new OutlineArc(1, "第一幕", "目标", "冲突", "转折", "结果", 4000, 6000, List.of(chapters))));
    }
}
