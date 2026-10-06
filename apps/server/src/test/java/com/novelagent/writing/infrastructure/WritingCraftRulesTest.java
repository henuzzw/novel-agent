package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class WritingCraftRulesTest {
    @Test
    void allStagesKeepFactStrategyAndRevisionBoundaries() {
        for (String rules : allStages()) {
            assertThat(rules).contains("有效正史", "未来计划不是已经发生的事实", "未知",
                    "不新编人物能力、道具权限、信息来源或帮助方向", "谁帮助谁",
                    "作者授权", "未受影响内容保持原样", "仅切换策略不授权重写旧素材", "OCCURRED",
                    "STANDARD", "FANQIE_GRIPPING", "不强制爽点", "类型回报", "硬配额");
        }
    }

    @Test
    void contractUsesExistingFieldsForExecutableBeatsAndPayoffs() {
        assertThat(WritingCraftRules.contract()).contains(
                "起点 / 意图 / 阻力或信息差 / 行动 / 结束变化 / 重要依据",
                "承诺 / 铺垫依据 / 本章兑现 / 余波", "requiredBeats", "expectedExitState",
                "requiredReveals、foreshadowActions、hook", "已有旧节拍可以沿用原文", "不增加输出字段或结构");
    }

    @Test
    void reviewChecksContractEvidenceWithoutInventingRepairsOrBlockingQuietScenes() {
        assertThat(WritingCraftRules.contractReview()).contains(
                "起点、意图、阻力或信息差、行动、结束变化及重要依据", "引用合同原文",
                "独立核对道具权限、帮助方向、人物能力与动机依据", "不新编来源修补合同",
                "旧合同未采用统一格式本身不是错误", "无法执行的合同可判 BLOCKING",
                "文学偏好、安静场景和依据不足不能直接判阻断", "只审阅不改写");
    }

    @Test
    void manuscriptTurnsPlannedBeatsIntoObservableProseWithinRevisionPermission() {
        assertThat(WritingCraftRules.manuscript()).contains(
                "起点、意图、阻力或信息差、行动与结束变化", "通过行动、对白和选择让读者看见证据",
                "不在小说正文中逐项列卡片", "本章明确安排的兑现需在正文成立",
                "仅语言润色时保留事件顺序、场景、人物认知与关系", "不擅自删并场景或改变情节",
                "未授权的结构问题交作者判断");
    }

    @Test
    void qualityRequiresActualBodyEvidenceAndOnlyDuePayoffs() {
        assertThat(WritingCraftRules.qualityReview()).contains(
                "只核对规划或合同明确标注为“本章兑现”的内容", "跨章长期承诺留给卷级检查",
                "起点、意图、阻力或信息差、行动、结束变化", "兑现落空、仅靠围观评价、重复场景功能",
                "body 中逐字存在的连续原文", "缺失内容不能伪造引文", "无法定位则说明无法判断",
                "仅有合同或被截断的正文不能宣称整章或三章兑现已校验", "开头片段只评估已展开内容",
                "正常概述、安静场景、必要过渡", "不擅自添加老师授权、临时能力或新剧情",
                "STYLE / FLUENCY / LOGIC / SCENE", "不新增评分维度", "不替作者执行修订或批准正史");
    }

    @Test
    void previewDoesNotRequireFullChapterOrThreeChapterPayoffs() {
        assertThat(WritingCraftRules.preview()).contains(
                "只试写开头片段", "主角意图、已展开行动及阻力或信息差", "局部变化用片段实际证据呈现",
                "不要求完成整章事件、整章结尾钩子、本章兑现或三章阶段兑现",
                "不能为了凑齐链条续写完整章节", "不抢写后续计划",
                "不因片段尚未兑现而报整章缺陷", "修订片段仅处理作者选中的问题",
                "语言润色不改事件、关系或帮助方向");
        assertThat(WritingCraftRules.preview()).doesNotContain("requiredBeats", "章节合同", "BLOCKING");
    }

    @Test
    void fullChapterRulesDistinguishGenrePayoffForeshadowAndHookWithoutQuotas() {
        for (String rules : List.of(WritingCraftRules.contract(), WritingCraftRules.contractReview(),
                WritingCraftRules.manuscript(), WritingCraftRules.qualityReview())) {
            assertThat(rules).contains("关系确认", "悬疑公平揭示", "选择与代价", "日常中的理解加深",
                    "大兑现追溯前文承诺与铺垫", "不等于本章兑现", "不靠反派降智、临时新增能力或巧合救场",
                    "避免重复同一铺垫、同型钩子与相同场景功能", "不是所有物件都要回收",
                    "安静章、压抑章、悲剧章不强制正向快感", "长期承诺不要求逐章兑现",
                    "不设置回报、反转、钩子、感官或段落长度的硬配额",
                    "同一大纲版本", "未提供相邻章计划或完整正文时说明依据不足");
        }
    }

    @Test
    void ruleTextIsStableAcrossRepeatedCalls() {
        assertThat(allStages()).containsExactlyElementsOf(allStages());
        assertThat(allStages()).doesNotHaveDuplicates();
    }

    private List<String> allStages() {
        return List.of(WritingCraftRules.contract(), WritingCraftRules.contractReview(),
                WritingCraftRules.manuscript(), WritingCraftRules.qualityReview(), WritingCraftRules.preview());
    }
}
