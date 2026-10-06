package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PlanningBatchAssemblerTest {
    private final StoryBibleContent bible = new StoryBibleContent("寻找失物", "信任", "校园", List.of(),
            "林安", "学会协作", List.of(), List.of(), "误会", "友情", "克制", "找到", List.of(), List.of());
    private final OutlineWordBudget budget = new OutlineWordBudget(4500, 3000, 6000, 1, 3, 1500, 1000, 2000);

    private PlanningCheckpoint chunk(int from, int to) {
        var plans = IntStream.rangeClosed(from, to).mapToObj(n -> new ChapterPlan(n, "线索" + n, "林安",
                "核对", "行动" + n, "信息", "下一步", 1000, 2000)).toList();
        var result = new PlanningCheckpointResult(List.of(new OutlineArc(1, "寻找", "调查", "阻力", "发现", "前进",
                (to - from + 1) * 1000, (to - from + 1) * 2000, plans)));
        return new PlanningCheckpoint(UUID.randomUUID(), UUID.randomUUID(), "chunk", from, to,
                new PlanningCheckpoint.Source(UUID.randomUUID(), 1, CreativeStrategyPolicy.of(CreativeStrategy.STANDARD),
                        ModelProvider.DEEPSEEK, "", "hash"), PlanningCheckpoint.Status.SUCCEEDED, 1, 2,
                result, null, Instant.now(), Instant.now());
    }

    @Test void successfulContinuousChunksBecomeADraftCompatibleFullOutline() {
        var content = PlanningBatchAssembler.assemble("失物", bible, budget, 3, List.of(chunk(1, 2), chunk(3, 3)));
        assertThat(content.chapterCount()).isEqualTo(3);
        assertThat(content.arcs()).extracting(OutlineArc::ordinal).containsExactly(1, 2);
        assertThat(content.arcs().stream().flatMap(arc -> arc.chapters().stream()).map(ChapterPlan::number))
                .containsExactly(1, 2, 3);
        assertThat(OutlineVersion.create(UUID.randomUUID(), UUID.randomUUID(), 1, "PLANNING_BATCH", null,
                UUID.randomUUID(), budget, content).getStatus()).isEqualTo(OutlineStatus.DRAFT);
    }

    @Test void missingTailCannotBecomeCompleteOutline() {
        assertThatThrownBy(() -> PlanningBatchAssembler.assemble("失物", bible, budget, 3, List.of(chunk(1, 2))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("尚未覆盖");
    }

    @Test void gapsAndOverlapsAreRejected() {
        assertThatThrownBy(() -> PlanningBatchAssembler.assemble("失物", bible, budget, 3,
                List.of(chunk(1, 1), chunk(3, 3)))).hasMessageContaining("连续");
        assertThatThrownBy(() -> PlanningBatchAssembler.assemble("失物", bible, budget, 3,
                List.of(chunk(1, 2), chunk(2, 3)))).hasMessageContaining("连续");
    }

    @Test void chapterWordsMustHaveIntersectionWithBookBudget() {
        var tooLong = new OutlineWordBudget(50_000, 40_000, 60_000, 1, 3, 16000, 12000, 20000);
        assertThatThrownBy(() -> PlanningBatchAssembler.assemble("失物", bible, tooLong, 3, List.of(chunk(1, 3))))
                .hasMessageContaining("没有交集");
    }

    @Test void occurredChaptersCannotBeReclassifiedAsFuture() {
        var occurred = new ChapterPlan(1, "线索", "林安", "查证", "行动", "信息", "下一步",
                1000, 2000, ChapterPlanStatus.OCCURRED);
        var result = new PlanningCheckpointResult(List.of(new OutlineArc(1, "调查", "目标", "误会", "线索", "结果",
                1000, 2000, List.of(occurred))));
        assertThatThrownBy(() -> result.requireRange(1, 1)).hasMessageContaining("已发生");
    }
}
