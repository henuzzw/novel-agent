package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlanningPromptConstraintTest {

    @Test
    void selectedOutlineIsIncludedAsCompleteStructuredJson() throws Exception {
        ChapterPlan finalChapter = new ChapterPlan(40, "最后一章", "男主", "做出选择",
                "最后一章独有事件", "真相揭示", "结局", 2400, 3600);
        OutlineContent selected = new OutlineContent("选定旧版", "前提", "结构", "节奏",
                110_000, 130_000, List.of(new OutlineArc(1, "第一卷", "目标", "冲突", "转折",
                "结果", 110_000, 130_000, List.of(finalChapter))));
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(),
                "主角", "弧光", List.of(), List.of(), "冲突", "代价", "叙事风格原文",
                "结局方向", List.of(), List.of("待确认问题原文"));
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000,
                3, 40, 3_000, 2_400, 3_600);
        ObjectMapper mapper = new ObjectMapper();

        String prompt = new OutlineModelPromptFactory(mapper).userPrompt(bible, budget, selected, "微调");
        String json = prompt.split("选定的基准大纲：", 2)[1].split("\\R\\R【调整原则】", 2)[0].trim();

        assertThat(mapper.readValue(json, OutlineContent.class)).isEqualTo(selected);
        assertThat(prompt).contains("最后一章独有事件", "叙事风格原文", "待确认问题原文");
        String bibleJson = prompt.split("【已发布故事圣经（完整 JSON）】", 2)[1]
                .split("【篇幅参考】", 2)[0].trim();
        assertThat(mapper.readValue(bibleJson, StoryBibleContent.class)).isEqualTo(bible);
    }

    private final CreativeIntentSnapshot intent = new CreativeIntentSnapshot(
            "一次换座位让五名学生的关系发生变化",
            List.of("青春校园"),
            "青春文学读者",
            "坐在第二排的男生",
            "暗恋、被喜欢和同桌关系彼此牵制",
            List.of("真实", "细腻"),
            120_000,
            null,
            List.of(
                    "考试成绩公布前，男主就暗自希望和喜欢的女生成为同桌",
                    "第二排从左到右依次是女学生1、女学生2、男主喜欢的女生、男主、喜欢男主的女生"),
            List.of(),
            List.of(),
            1L);

    @Test
    void storyDirectionPromptRequiresEveryCandidateToImplementMustHaveFacts() {
        StoryDirectionModelPromptFactory factory = new StoryDirectionModelPromptFactory();

        assertThat(factory.systemPrompt())
                .contains("三个候选方向都必须逐条落实")
                .contains("不得遗漏、改变关键含义");
        assertThat(factory.userPrompt(intent, List.of(), null))
                .contains("考试成绩公布前")
                .contains("第二排从左到右")
                .contains("生成前逐条检查");
    }

    @Test
    void storyBiblePromptCarriesMustHaveFactsIntoHardConstraints() {
        StoryBibleModelPromptFactory factory = new StoryBibleModelPromptFactory();
        StoryDirectionCandidate direction = new StoryDirectionCandidate(
                UUID.randomUUID(), "五人同桌", "故事前提", "核心冲突", "主角弧光", "三幕结构", "结局",
                "目标读者", List.of(), List.of(), List.of());

        assertThat(factory.systemPrompt())
                .contains("逐条写入 hardConstraints")
                .contains("供大纲和正文阶段继续执行");
        assertThat(factory.userPrompt(intent, direction, null, null))
                .contains("考试成绩公布前")
                .contains("第二排从左到右")
                .contains("不要合并到无法核对");
    }

    @Test
    void revisionPromptsCarryTheCurrentVersionAndRequireLimitedChanges() {
        StoryDirectionCandidate direction = new StoryDirectionCandidate(
                UUID.randomUUID(), "原方向", "原故事前提", "原核心冲突", "原主角弧光", "原结构", "原结局",
                "目标读者", List.of(), List.of(), List.of());
        StoryBibleContent bible = new StoryBibleContent(
                "原圣经故事", "原主题", "原世界", List.of("原规则"), "原主角", "原弧光", List.of(),
                List.of(), "原冲突", "原代价", "原文风", "原结局", List.of("原硬约束"), List.of());

        assertThat(new StoryDirectionModelPromptFactory().userPrompt(intent, List.of(direction), "微调"))
                .contains("原故事前提", "不要借机整体重写", "changeSummary", "实际修改");
        assertThat(new StoryBibleModelPromptFactory().userPrompt(intent, direction, bible, "微调"))
                .contains("原圣经故事", "只修改", "changeSummary", "实际改动");
        OutlineContent outline = new OutlineContent(
                "原大纲", "原大纲前提", "原结构", "原节奏", 110_000, 130_000, List.of());
        OutlineWordBudget budget = new OutlineWordBudget(
                120_000, 110_000, 130_000, 3, 40, 3_000, 2_400, 3_600);
        assertThat(new OutlineModelPromptFactory(new com.fasterxml.jackson.databind.ObjectMapper())
                .userPrompt(bible, budget, outline, "微调"))
                .contains("原大纲前提", "未受影响的章节编号", "完整新版本", "changeSummary", "实际改动");

        assertThat(new StoryBibleModelPromptFactory().userPrompt(intent, direction, null, null))
                .contains("changeSummary 必须返回空数组");
    }

    @Test
    void outlineModesKeepTheirDifferentStatusAndVersionRules() {
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(),
                "主角", "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000,
                3, 40, 3_000, 2_400, 3_600);
        ChapterPlan occurred = new ChapterPlan(1, "已发生的相遇", "主角", "相遇", "换座位", "认出同桌",
                "新的疑问", 2_400, 3_600, ChapterPlanStatus.OCCURRED);
        OutlineContent selected = new OutlineContent("旧版", "前提", "结构", "节奏", 110_000,
                130_000, List.of(new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                110_000, 130_000, List.of(occurred))));
        OutlineModelPromptFactory factory = new OutlineModelPromptFactory(new ObjectMapper());

        String fresh = factory.userPrompt(bible, budget, null, null);
        assertThat(fresh).contains("【生成方式：从头规划】", "不参考任何旧版",
                "status 全部设为 PLANNED", "changeSummary 返回空数组");
        assertThat(fresh).doesNotContain("选定的基准大纲：");

        String revision = factory.userPrompt(bible, budget, selected, "只改第三卷");
        assertThat(revision).contains("【生成方式：基于选定版本调整】", "\"status\" : \"OCCURRED\"",
                "不得把已发生的 OCCURRED 章节改成 PLANNED", "篇幅参考本身不是增删章节的理由",
                "只改第三卷");
        assertThat(revision).doesNotContain("status 全部设为 PLANNED");
    }
}
