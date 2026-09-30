package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutlineVersionTest {
    @Test
    void acceptsFuzzyChapterCapacityAndLocksPublishedVersion() {
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000, 2, 40, 3_000, 2_400, 3_600);
        OutlineVersion version = OutlineVersion.create(UUID.randomUUID(), UUID.randomUUID(), 1, "TEST", null,
                UUID.randomUUID(), budget, content("初稿", 2_400, 3_600));

        version.revise(content("修订稿", 1_800, 4_200));
        version.publish();

        assertThat(version.getContent().title()).isEqualTo("修订稿");
        assertThat(version.getContent().arcs().getFirst().chapters().getFirst().suggestedMinWords()).isEqualTo(1_800);
        assertThatThrownBy(() -> version.revise(content("再次修改", 2_000, 4_000)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsWholeBookRangeOutsideAcceptedTolerance() {
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000, 2, 40, 3_000, 2_400, 3_600);
        OutlineContent invalid = new OutlineContent("标题", "前提", "结构", "节奏", 90_000, 150_000,
                List.of(arc(2_400, 3_600)));

        assertThatThrownBy(() -> OutlineVersion.create(UUID.randomUUID(), UUID.randomUUID(), 1, "TEST", null,
                UUID.randomUUID(), budget, invalid)).isInstanceOf(IllegalArgumentException.class);
    }

    private static OutlineContent content(String title, int chapterMin, int chapterMax) {
        return new OutlineContent(title, "前提", "结构", "节奏", 110_000, 130_000,
                List.of(arc(chapterMin, chapterMax)));
    }

    private static OutlineArc arc(int min, int max) {
        ChapterPlan chapter = new ChapterPlan(1, "第一章", "主角", "目标", "事件", "揭示", "钩子", min, max);
        return new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果", 50_000, 70_000, List.of(chapter));
    }
}
