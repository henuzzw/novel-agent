package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class CreativeStrategyGuideTest {
    @Test
    void grippingOpeningTargetsRelevantPressureChoicesAndGenrePayoffNotOnlyFluency() {
        String guide = CreativeStrategyGuide.render(CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));
        assertThat(guide).contains("强开篇不是文风", "为何此刻必须回应", "不算有效开场压力",
                "只展开会改变选择", "第二章", "第三章", "偏爱被看见", "不等于关系题材的有效兑现",
                "不把情绪回报全部拖到第三章", "OCCURRED", "有证据才报问题");
    }

    @Test
    void standardDoesNotInheritGrippingRequirements() {
        assertThat(CreativeStrategyGuide.render(CreativeStrategyPolicy.of(CreativeStrategy.STANDARD)))
                .contains("标准创作", "允许有效的安静场景", "不强制每章反转")
                .doesNotContain("为何此刻必须回应", "偏爱被看见", "番茄强开篇");
    }

    @Test
    void stageGuidesAreConditionalAndDoNotExpandSchemasOrInventFacts() {
        for (String rules : List.of(CreativeStrategyGuide.outlineRules(), CreativeStrategyGuide.contractRules(),
                CreativeStrategyGuide.manuscriptRules(), CreativeStrategyGuide.reviewRules(),
                CreativeStrategyGuide.previewRules(), CreativeStrategyGuide.previewReviewRules())) {
            assertThat(rules).contains("FANQIE_GRIPPING", "STANDARD");
        }
        assertThat(CreativeStrategyGuide.outlineRules()).contains("objective/coreEvent/reveal/endingHook",
                "不增加字段", "precedingPlans", "不越界重写");
        assertThat(CreativeStrategyGuide.contractRules()).contains("requiredBeats", "expectedExitState",
                "上游缺口待作者确认", "不编造危机");
        assertThat(CreativeStrategyGuide.manuscriptRules()).contains("不影响选择的数学解法",
                "未授权结构缺口交作者处理", "不强制首章模式");
        assertThat(CreativeStrategyGuide.reviewRules()).contains("上游大纲缺少压力", "两类问题",
                "开篇吸引力不足本身不是 BLOCKING", "不能用章末悬念替开头通过");
        assertThat(CreativeStrategyGuide.previewReviewRules()).contains("不能因文句通顺", "body 连续原文",
                "未走到回报不等于整章兑现失败");
    }
}
