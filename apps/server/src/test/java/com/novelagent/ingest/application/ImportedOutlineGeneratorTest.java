package com.novelagent.ingest.application;

import static org.assertj.core.api.Assertions.*;

import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportedOutlineGeneratorTest {
    @Test void assignsOccurredStatusesWithoutChangingChapterText() {
        var generated = outline(3);
        var normalized = ImportedOutlineGenerator.normalizeChapterStatuses(generated, 2);
        var chapters = normalized.content().arcs().getFirst().chapters();
        assertThat(chapters).extracting(ChapterPlan::status)
                .containsExactly(ChapterPlanStatus.OCCURRED, ChapterPlanStatus.OCCURRED, ChapterPlanStatus.PLANNED);
        assertThat(chapters).extracting(ChapterPlan::content)
                .containsExactlyElementsOf(generated.content().arcs().getFirst().chapters().stream().map(ChapterPlan::content).toList());
        assertThat(ImportedOutlineGenerator.normalizeChapterStatuses(generated, 0).content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::status).containsOnly(ChapterPlanStatus.PLANNED);
        assertThatThrownBy(() -> ImportedOutlineGenerator.normalizeChapterStatuses(outline(1), 2))
                .hasMessageContaining("少于已导入章节数");
    }

    private GeneratedOutline outline(int count) {
        var chapters = java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ChapterPlan(i, "章节" + i, "完整场景与行动后果" + i, ChapterPlanStatus.PLANNED, false)).toList();
        var arc = new OutlineArc(1, "第一阶段", "目标", "冲突", "转折", "结果", 20000, 40000, chapters);
        return new GeneratedOutline("TEST", new OutlineContent("书名", "前提", "结构", "节奏", 50000, 60000, List.of(arc)));
    }
}
