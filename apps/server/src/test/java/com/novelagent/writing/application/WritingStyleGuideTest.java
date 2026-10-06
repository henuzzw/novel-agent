package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingStyleGuideTest {
    @Test
    void everyPresetHasItsOwnTechniqueExamplesAndSharedReviewCriteria() {
        var guides = WritingStylePresets.all().stream().map(WritingStyleGuide::render).toList();
        assertThat(guides).hasSize(11).doesNotHaveDuplicates();
        for (var profile : WritingStylePresets.all()) {
            assertThat(WritingStyleGuide.render(profile)).contains(profile.name(), profile.narrativeVoice(),
                    profile.sentenceRhythm(), profile.descriptionFocus(), profile.dialogueStyle(),
                    profile.emotionalExpression(), profile.pacing(), "本风格具体写法：", "正例：",
                    "反例（不要模仿）：", "区别：", "检查标准：STYLE", "正文连续原文",
                    "事实与硬约束优先", "不得复制句子、人物、物件或事件到正文", "润色边界：",
                    "不改事件顺序、知识、关系和结局");
            assertThat(WritingStyleGuide.render(profile)).contains(profile.avoidPatterns());
        }
    }

    @Test
    void lightConversationalStyleExplainsSentenceHumorAndSceneBoundaries() {
        var guide = WritingStyleGuide.render(WritingStylePresets.all().get(1));
        assertThat(guide).contains("旁白像自然讲述", "完整中长句交代误会或观察", "不给每段安排笑点",
                "不把真实悲伤强行写成笑话", "快不等于概述一切", "轻快不等于碎句");
    }

    @Test
    void customProfileDoesNotInheritExamplesFromMatchingName() {
        var original = WritingStylePresets.all().get(1);
        var custom = new WritingStyleProfile(original.name(), "庄重而疏离", original.sentenceRhythm(),
                original.descriptionFocus(), original.dialogueStyle(), original.emotionalExpression(),
                original.pacing(), original.avoidPatterns());
        assertThat(WritingStyleGuide.render(custom)).contains("庄重而疏离", "自定义/已编辑档案",
                "不按名称套用预设或猜测作家").doesNotContain("本风格具体写法：", "正例：", "绝绝子");
    }

    @Test
    void renamedOrAnalyzedProfileKeepsItsOwnFieldsAndNoPresetExamples() {
        var original = WritingStylePresets.all().getFirst();
        var custom = new WritingStyleProfile("样本分析：我的风格", original.narrativeVoice(),
                original.sentenceRhythm(), original.descriptionFocus(), original.dialogueStyle(),
                original.emotionalExpression(), original.pacing(), List.of("不复述样本情节"));
        assertThat(WritingStyleGuide.render(custom)).contains(custom.name(), "不复述样本情节",
                "不确定的特征不擅自补齐", "字段是表达数据").doesNotContain("正例：", "本风格具体写法：");
    }

    @Test
    void referencePresetsKeepDifferentRhetoricalChoicesAndHardBoundaries() {
        var presets = WritingStylePresets.all();
        assertThat(WritingStyleGuide.render(presets.get(7))).contains("比喻一次照亮一项具体观察", "不每句造警句");
        assertThat(WritingStyleGuide.render(presets.get(8))).contains("舒缓不等于列清单", "不以温柔口吻抹平");
        assertThat(WritingStyleGuide.render(presets.get(9))).contains("可以思考", "不为荒诞改世界规则");
        assertThat(WritingStyleGuide.render(presets.get(10))).contains("不用作家名字授权增加悲剧");
    }

    @Test
    void maximumCustomProfileRemainsBoundedAndRenderingIsDeterministic() {
        var profile = new WritingStyleProfile("名".repeat(80), "声".repeat(600), "句".repeat(600),
                "描".repeat(600), "白".repeat(600), "情".repeat(600), "速".repeat(600),
                java.util.Collections.nCopies(12, "避".repeat(120)));
        var guide = WritingStyleGuide.render(profile);
        assertThat(guide.length()).isLessThan(8500);
        assertThat(WritingStyleGuide.render(profile)).isEqualTo(guide);
    }

    @Test
    void editedAndRenamedPresetKeepsSavedTechniquesButAdjustmentsTakePriority() {
        var original = WritingStylePresets.all().get(6);
        var edited = new WritingStyleProfile("校园人情", "庄重，减少幽默", original.sentenceRhythm(),
                original.descriptionFocus(), original.dialogueStyle(), original.emotionalExpression(),
                original.pacing(), original.avoidPatterns(), original.basePresetId(), original.basePresetVersion(), original.craft());
        assertThat(WritingStyleGuide.render(edited)).contains("校园人情", "庄重，减少幽默", "street-humor",
                "段落组织：", "修辞机制：", "选座与让位", "修理铺结账", "当前字段及作者本次明确调整优先")
                .doesNotContain("自定义/已编辑档案");
        assertThat(original.name()).isEqualTo("老舍参考：市井幽默");
    }

    @Test
    void allPresetsHaveMultipleScenesAndDiscriminativeMechanicsNotJustLabels() {
        for (var profile : WritingStylePresets.all()) {
            assertThat(profile.basePresetId()).isNotBlank();
            assertThat(profile.basePresetVersion()).isEqualTo(1);
            assertThat(profile.craft().examples()).hasSize(2);
            assertThat(profile.craft().paragraphMoves()).hasSizeGreaterThan(50);
            assertThat(profile.craft().rhetoricMoves()).hasSizeGreaterThan(50);
            assertThat(profile.craft().sceneVariants()).containsAnyOf("高潮", "揭示");
            assertThat(WritingStyleGuide.render(profile).length()).isLessThan(7500);
        }
        assertThat(WritingStylePresets.all().stream().map(p -> p.craft().paragraphMoves()).toList()).doesNotHaveDuplicates();
    }
}
