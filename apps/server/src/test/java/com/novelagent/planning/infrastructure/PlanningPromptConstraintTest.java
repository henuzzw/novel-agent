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
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
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

    @Test
    void outlineRevisionPutsAuthorRequestBeforeLongContextAndChecksItAgainAtTheEnd() {
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(),
                "主角", "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        OutlineWordBudget budget = new OutlineWordBudget(120_000, 110_000, 130_000,
                3, 40, 3_000, 2_400, 3_600);
        OutlineContent selected = new OutlineContent("旧版", "前提", "结构", "节奏",
                110_000, 130_000, List.of());
        OutlineModelPromptFactory factory = new OutlineModelPromptFactory(new ObjectMapper());

        String prompt = factory.userPrompt(bible, budget, selected, "只修改第三卷的结尾");

        assertThat(prompt.indexOf("【作者本次要求（本轮修改重点）】"))
                .isLessThan(prompt.indexOf("【已发布故事圣经（完整 JSON）】"));
        assertThat(prompt.indexOf("只修改第三卷的结尾"))
                .isLessThan(prompt.indexOf("选定的基准大纲："));
        assertThat(factory.systemPrompt()).contains("先识别作者本次要求具体影响的卷、章节和字段");
        assertThat(prompt).contains("逐条核对开头的作者本次要求是否落实",
                "未受影响的卷章、因果顺序和 status 必须保持原样");
        assertThat(prompt.lastIndexOf("逐条核对开头的作者本次要求是否落实"))
                .isGreaterThan(prompt.indexOf("选定的基准大纲："));
    }

    @Test
    void authorOpeningPrecedesSeparatePolicyAndIsNotReplacedByStrongOpeningAdvice() {
        var factory = new OutlineModelPromptFactory(new ObjectMapper());
        String author = "开头是毕业工作后的许言川发现QQ空间私密相册少了一张高中照片。\n保留回忆框架，不编造删除者。";
        String prompt = factory.userPrompt(craftBible(), craftBudget(), null, author,
                CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));
        String authorBlock = prompt.split("【作者本次要求（本轮修改重点）】", 2)[1]
                .split("【依据优先级】", 2)[0];
        String policyBlock = prompt.split("【项目创作策略（系统辅助规则，不是作者原文）】", 2)[1]
                .split("【已发布故事圣经（完整 JSON）】", 2)[0];

        assertThat(authorBlock).contains(author).doesNotContain("番茄强开篇", "项目创作策略：");
        assertThat(policyBlock).contains("项目创作策略：FANQIE_GRIPPING", "不是作者本轮原文",
                "不能为了套用强开篇删除作者指定", "不虚构删除者、动机或危机");
        assertThat(prompt.indexOf(author)).isLessThan(prompt.indexOf("项目创作策略：FANQIE_GRIPPING"));
        assertThat(factory.systemPrompt()).contains("作者本轮明确要求优先于系统生成的项目策略",
                "不为强开篇擅自更换场景或补造危机");
        assertThat(prompt).contains("OCCURRED", "已确认事实", "未执行的要求、冲突依据",
                "不得用项目策略取代本轮要求", "不增加输出字段");
    }

    @Test
    void emptyAuthorRequestDoesNotTurnPolicyIntoAuthorTextOrRewritePermission() {
        String prompt = new OutlineModelPromptFactory(new ObjectMapper()).userPrompt(
                craftBible(), craftBudget(), new OutlineContent("旧版", "前提", "结构", "节奏",
                        110_000, 130_000, List.of()), "  ",
                CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));
        String authorBlock = prompt.split("【作者本次要求（本轮修改重点）】", 2)[1]
                .split("【依据优先级】", 2)[0];
        assertThat(authorBlock.trim()).startsWith("无").contains("不表示作者授权整体重写")
                .doesNotContain("番茄强开篇", "项目创作策略：");
        assertThat(prompt).contains("仅切换 policy 不构成重写授权",
                "不能仅因策略建议改变而重写");
    }

    @Test
    void policyConfigurationIsNotInferredFromWordsInAuthorRequest() {
        String author = "讨论 FANQIE_GRIPPING，但本轮只改第三卷结尾，开头保持慢热。";
        String prompt = new OutlineModelPromptFactory(new ObjectMapper()).userPrompt(
                craftBible(), craftBudget(), null, author, CreativeStrategyPolicy.of(CreativeStrategy.STANDARD));
        String policyBlock = prompt.split("【项目创作策略（系统辅助规则，不是作者原文）】", 2)[1]
                .split("【已发布故事圣经（完整 JSON）】", 2)[0];
        assertThat(prompt).contains(author, "不从作者原文推断项目配置");
        assertThat(policyBlock).contains("项目创作策略：STANDARD").doesNotContain("番茄强开篇：");
    }

    @Test
    void outlineDesignsOneThreeChapterArcOnlyUnderExplicitStrongOpeningPolicy() {
        OutlineModelPromptFactory factory = new OutlineModelPromptFactory(new ObjectMapper());
        String prompt = factory.userPrompt(craftBible(), craftBudget(), null,
                "偏向关系驱动。", CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));

        assertThat(prompt).contains("项目创作策略：FANQIE_GRIPPING；策略版本：1。", "偏向关系驱动。",
                "【整份大纲的前三章短弧】", "第 1～3 章一起设计", "开场问题 -> 主角行动 -> 阻力与代价 -> 第一轮兑现 -> 更长线目标",
                "第一章：", "第一次真实回报或不可逆变化", "第二章：", "回应第一章一个具体期待",
                "第三章：", "用前两章已有铺垫", "明确已兑现与长期未兑现的承诺",
                "目标、回报和钩子落到同一份大纲的现有章节字段", "不重复设定介绍、心理结论或同型冲突");
    }

    @Test
    void standardOrMissingPolicyPreservesGenrePaceAndExistingOutputShape() {
        OutlineModelPromptFactory factory = new OutlineModelPromptFactory(new ObjectMapper());
        for (String instruction : List.of("保留慢热节奏。", "无")) {
            String prompt = factory.userPrompt(craftBible(), craftBudget(), null, instruction,
                    CreativeStrategyPolicy.of(CreativeStrategy.STANDARD));
            assertThat(prompt).contains(instruction, "STANDARD 或未明确提供 FANQIE_GRIPPING",
                    "不强制爽点或前三章强开篇", "按题材与作者节奏", "作者明确选择慢热文学叙事时保留该选择",
                    "objective/coreEvent/reveal/endingHook", "承诺 / 铺垫依据 / 本章兑现 / 余波", "不增加输出字段",
                    "起点、意图、阻力或信息差、行动、结束变化与重要依据", "安静章、压抑章和悲剧章不强制正向快感",
                    "不设置反转、回报或钩子的硬配额", "关系确认", "悬疑公平揭示", "成长的选择与代价", "日常理解",
                    "不新编人物能力、道具权限、信息来源或帮助方向", "不靠围观夸赞、反派降智、临时能力或巧合救场",
                    "不连续重复同一铺垫、同型钩子或场景功能");
        }
    }

    @Test
    void strongOpeningPolicyCannotAutomaticallyRewriteUnaffectedOrOccurredMaterial() {
        ChapterPlan occurred = new ChapterPlan(1, "已发生的选择", "主角", "原目标", "原事件",
                "原揭示", "原钩子", 2400, 3600, ChapterPlanStatus.OCCURRED);
        OutlineContent selected = new OutlineContent("旧大纲", "前提", "结构", "节奏", 110_000,
                130_000, List.of(new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                110_000, 130_000, List.of(occurred))));
        String prompt = new OutlineModelPromptFactory(new ObjectMapper()).userPrompt(
                craftBible(), craftBudget(), selected, "只调整第三卷结尾。",
                CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));

        assertThat(prompt).contains("只调整第三卷结尾", "\"status\" : \"OCCURRED\"", "原事件",
                "从头规划或获授权调整前三章时", "不自动改写旧版未受影响大纲",
                "不为统一格式改写旧字段", "仅切换 policy 不构成重写授权",
                "OCCURRED 章节及已确认事实不得为开篇强度自动改写", "既有素材不足时指出限制交作者决定",
                "未受影响的卷章、因果顺序和 status 必须保持原样");
    }

    private StoryBibleContent craftBible() {
        return new StoryBibleContent("故事", "主题", "世界", List.of(), "主角", "弧光", List.of(),
                List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
    }

    private OutlineWordBudget craftBudget() {
        return new OutlineWordBudget(120_000, 110_000, 130_000, 3, 40, 3_000, 2_400, 3_600);
    }
}
