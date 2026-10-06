package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CreationPreparationTest {
    private final CreationPreparation.World world = new CreationPreparation.World(
            List.of(CharacterBlueprintFixtures.character("林安")), List.of());
    private CreationPreparation.Unit unit(String key, int start, int end) {
        return new CreationPreparation.Unit(key, "寻找失物", start, end, "找回纸条", "误会", "发现字迹", "说明真相", List.of("林安"), List.of());
    }
    @Test void plansByPlotBoundaryWithoutAnyWordThreshold() {
        new CreationPreparation.Plot(List.of(unit("all", 1, 20)), List.of(), List.of(), List.of(), List.of())
                .validate(world, java.util.stream.IntStream.rangeClosed(1, 20).boxed().collect(java.util.stream.Collectors.toSet()), 1, 20);
        new CreationPreparation.Plot(List.of(unit("a", 1, 2), unit("b", 3, 4)), List.of(), List.of(), List.of(), List.of())
                .validate(world, Set.of(1, 2, 3, 4), 1, 4);
    }
    @Test void rejectsOverlapsGapsMissingPeopleAndInvalidChapterReferences() {
        assertThatThrownBy(() -> new CreationPreparation.Plot(List.of(unit("a", 1, 2), unit("b", 2, 4)), List.of(), List.of(), List.of(), List.of())
                .validate(world, Set.of(1, 2, 3, 4), 1, 4)).hasMessageContaining("连续");
        assertThatThrownBy(() -> new CreationPreparation.Plot(List.of(unit("a", 1, 2)), List.of(),
                List.of(new CreationPreparation.Knowledge("不存在的人", "秘密", 2, "亲眼看见")), List.of(), List.of())
                .validate(world, Set.of(1, 2), 1, 2)).hasMessageContaining("人物引用");
        assertThatThrownBy(() -> new CreationPreparation.Plot(List.of(unit("a", 1, 2)), List.of(), List.of(),
                List.of(new CreationPreparation.Timeline("event", 3, "下午", "告别", List.of("林安"))), List.of())
                .validate(world, Set.of(1, 2), 1, 2)).hasMessageContaining("章节");
    }
    @Test void requiresExactFieldEvidenceAndNeverAdjustsCommittedChapters() throws Exception {
        var source = new ObjectMapper().readTree("{\"world_design\":{\"text\":\"纸条在书里\"}}");
        var issue = new CreationPreparation.Issue("item", "WARNING", "物品", "需核对归属", "/world_design/text", "纸条", "核对来源");
        new CreationPreparation.Review("规划检查", List.of(issue), List.of()).validate(source, Set.of(1, 2), 1);
        assertThatThrownBy(() -> new CreationPreparation.Review("规划检查", List.of(new CreationPreparation.Issue("item", "WARNING", "物品", "需核对", "/world_design/text", "不在原文", "核对来源")), List.of()).validate(source, Set.of(1, 2), 1))
                .hasMessageContaining("逐字");
        assertThatThrownBy(() -> new CreationPreparation.Review("规划检查", List.of(), List.of(new CreationPreparation.Adjustment(1, "新目标", "新事件", "", "", "变化"))).validate(source, Set.of(1, 2), 1))
                .hasMessageContaining("最后正史");
    }
    @Test void rejectsDuplicateNamesAndKeys() {
        assertThatThrownBy(() -> new CreationPreparation.World(List.of(world.characters().getFirst(), world.characters().getFirst()), List.of())).hasMessageContaining("重复");
        assertThatThrownBy(() -> new CreationPreparation.Entity("bad key", "ITEM", "纸条", "", "", "")).hasMessageContaining("key");
    }
}
