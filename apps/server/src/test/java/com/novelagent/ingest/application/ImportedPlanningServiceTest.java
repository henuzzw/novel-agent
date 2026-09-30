package com.novelagent.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportedPlanningServiceTest {
    @Test
    void deterministicallySeparatesOccurredAndPlannedChapters() {
        GeneratedOutline normalized = ImportedPlanningService.normalizeChapterStatuses(outline(3), 2);

        assertThat(normalized.content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::status)
                .containsExactly(ChapterPlanStatus.OCCURRED, ChapterPlanStatus.OCCURRED,
                        ChapterPlanStatus.PLANNED);
        assertThat(ImportedPlanningService.normalizeChapterStatuses(outline(3), 0)
                .content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::status)
                .containsOnly(ChapterPlanStatus.PLANNED);
        assertThatThrownBy(() -> ImportedPlanningService.normalizeChapterStatuses(outline(1), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("少于已导入章节数");
    }

    private static GeneratedOutline outline(int chapterCount) {
        List<ChapterPlan> chapters = java.util.stream.IntStream.rangeClosed(1, chapterCount)
                .mapToObj(number -> new ChapterPlan(number, "第" + number + "章", "主角", "目标", "事件",
                        "揭示", "钩子", 2_000, 4_000, ChapterPlanStatus.PLANNED))
                .toList();
        OutlineArc arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                20_000, 40_000, chapters);
        return new GeneratedOutline("TEST", new OutlineContent("书名", "前提", "结构", "节奏",
                50_000, 60_000, List.of(arc)));
    }
}
