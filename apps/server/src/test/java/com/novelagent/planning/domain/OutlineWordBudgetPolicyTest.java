package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class OutlineWordBudgetPolicyTest {

    private final OutlineWordBudgetPolicy policy = new OutlineWordBudgetPolicy();

    @Test
    void createsFuzzyWholeBookAndChapterRanges() {
        OutlineWordBudget budget = policy.plan(200_000);

        assertThat(budget.acceptableMinWords()).isEqualTo(190_000);
        assertThat(budget.acceptableMaxWords()).isEqualTo(210_000);
        assertThat(budget.recommendedChapterMinWords()).isLessThan(budget.averageChapterWords());
        assertThat(budget.recommendedChapterMaxWords()).isGreaterThan(budget.averageChapterWords());
    }

    @Test
    void createsAStablePlanForACommonLongNovelTarget() {
        OutlineWordBudget budget = policy.plan(120_000);

        assertThat(budget.recommendedChapterCount()).isEqualTo(40);
        assertThat(budget.recommendedVolumeCount()).isEqualTo(2);
        assertThat(budget.averageChapterWords()).isEqualTo(3_000);
        assertThat(budget.acceptableMinWords()).isEqualTo(110_000);
        assertThat(budget.acceptableMaxWords()).isEqualTo(130_000);
    }

    @Test
    void requiresATargetBeforeOutlineGeneration() {
        assertThatThrownBy(() -> policy.plan(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标字数");
    }
}
