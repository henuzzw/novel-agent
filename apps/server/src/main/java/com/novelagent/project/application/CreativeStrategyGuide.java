package com.novelagent.project.application;

import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;

/**
 * 项目创作策略的提示词片段。各阶段“落点”规则只在 FANQIE_GRIPPING 下返回内容，
 * STANDARD 不拼接强开篇指标，避免依赖模型自行忽略不适用的规则。
 */
public final class CreativeStrategyGuide {
    public static String render(CreativeStrategyPolicy policy) {
        String common = "项目创作策略：" + policy.strategy().name() + "；策略版本：" + policy.policyVersion()
                + "。策略控制结构与读者期待，不替代写作风格，不改变事实、人物能力、视角或作者确认内容。\n"
                + "策略是系统辅助规则，不是作者本轮原文；在本阶段有效上游约束和修订授权内，作者本轮明确要求优先于通用策略建议。\n"
                + "作者明确指定的开场、回忆框架或节奏不得仅为套用策略而更换；在既定选择内改善因果和阅读期待，上游冲突交作者确认，不擅改事实。\n";
        if (!gripping(policy)) {
            return common + "标准创作：按故事类型与章节目标组织场景，允许有效的安静场景和必要过渡；第一章仍执行大纲默认开篇要求，不强制后续每章反转或高强度冲突。";
        }
        return common + """
                番茄强开篇：新规划和新创作的前三章以紧凑因果和可信回报建立阅读期待。
                强开篇不是文风：清爽、克制、温情或市井幽默只控制表达，不能把有压力的情节降成礼貌聊天和顺利完成日常事务。
                第一章开头几个自然段进入具体处境与迫切问题，主角采取行动、遭遇阻力，整章获得可观察的第一次进展；首段优先呈现矛盾中的动作、带目的的对白或影响眼前处境的异常，不以背景说明占满，不靠夸张措辞假称危机。
                让读者知道主角想得到什么、为何此刻必须回应、不行动会失去什么。悬念必须作用于当前行动；与眼前场景无因果联系的远期预告不算有效开场压力。
                背景按行动需要补充，不先回顾成长史或解释全部关系；日常过程只展开会改变选择、关系或信息的部分。
                第二章承接第一次行动的后果，推动问题或关系发生新的变化，不重复第一章的场景功能。
                第三章用已出现的能力、信息、物件与协作兑现第一轮承诺，保留代价和长线目标；不临时开挂，不靠围观夸奖宣布成功。
                回报因类型而异：关系推进、发现证据、摆脱困境、赢得信任或争取主动权均可，不强加打脸或羞辱；须有既定依据，不强造暗恋或三角关系。
                完成普通事务、大家笑了、主角松口气本身不等于有效兑现；如果确实改变信任或处境，写清行动证据和后果，不靠旁白宣布。
                小进展与受阻交替推进，不把情绪回报全部拖到第三章或章末；章末留下由结果引出的具体新问题，不机械安排反转配额。
                仅切换策略不授权重写旧稿、OCCURRED 章节或已确认事实；大纲调整只修改作者授权或上游约束真正影响的部分。
                """;
    }

    public static boolean gripping(CreativeStrategyPolicy policy) {
        return policy != null && policy.strategy() == CreativeStrategy.FANQIE_GRIPPING;
    }

    public static String outlineRules(CreativeStrategyPolicy policy) {
        if (!gripping(policy)) return "";
        return """
                \n【强开篇大纲落点】
                仅获授权新规划或调整的第 1～3 章适用；后续章按各自目标设计。
                第一章的 objective 写清眼前欲望与不回应的代价；coreEvent 写出开场正在发生的矛盾、第一步行动、阻力或信息差、关键选择及可观察的第一次回报。
                reveal 区分读者本章新获得的信息与人物已知内容；endingHook 必须是本章选择或回报引出的下一步风险、要求或待作决定，不填“更大危机即将到来”。
                第二章承接该选择的实际后果，改变局面并回应首章期待；第三章凭前两章铺垫兑现短弧核心期待，同时留下具体代价与长线目标。
                逐章写出不同的场景功能和变化；素材中的日常事务只展开真正造成拉扯、发现、行动或后果的部分，不得虚构动机补强。
                在现有 objective/coreEvent/reveal/endingHook 内落实，不增加字段；分块只规划本次范围，承接 precedingPlans，不越界重写或假称已完成三章设计。
                依据不足在相关现有字段说明待确认，不偷改已发生内容。
                """;
    }

    public static String manuscriptRules(CreativeStrategyPolicy policy) {
        if (!gripping(policy)) return "";
        return """
                \n【强开篇正文落点】
                仅新创作的第 1～3 章适用；修订仍服从作者授权。
                第一章开头几个自然段落地本章计划已有的开场压力，让人物已在回应问题；回忆框架有约束时保留，但压缩无关铺陈并尽快接入当下矛盾。
                关键选择前保留必要信息，选择时展开对白、动作和阻力，选择后给可观察的回应或后果；不把紧凑写成缺少因果的快进。
                不逐步演示不影响选择的操作流程，不为目标字数扩写；让本章计划安排的回报在场景中可感知，不用一句“懂了”“大家笑了”宣布冲突解决。
                输出前静默核对开场压力、行动、选择、回报及章末新问题；不能为达标新增事实或擅改本章计划，未授权结构缺口交作者处理。
                """;
    }

    public static String reviewRules(CreativeStrategyPolicy policy) {
        if (!gripping(policy)) return "";
        return """
                \n【强开篇审阅落点】
                仅第 1～3 章检查这些设计目标，不保证番茄留存或流量。
                首章分开检查最初几个自然段是否呈现当前压力与已展开行动，以及整章是否有选择后果、真实回报和结果引出的下一步问题；不能用章末悬念替开头通过。
                检查回报是否改变关系、认知或处境；完成普通事务或一起笑若没有这些变化，不应直接认作兑现。
                定位无因果接入的远期预告、重复背景与心理解释、没有信息变化的操作流程；用具体原文说明问题，不按关键词或固定字数判失败。
                上游大纲缺少压力或回报设计与正文没有执行已有节拍是两类问题，分别指明；不建议虚构关系、动机或事件来填缺口。
                未提供完整范围不判断整章结尾或三章兑现；开篇吸引力不足本身不是 BLOCKING，不自动改稿或放行正史。
                """;
    }

    public static String previewRules(CreativeStrategyPolicy policy) {
        if (!gripping(policy)) return "";
        return """
                \n【强开篇试写落点】
                从第一章计划已有的最早有效矛盾进入，在开头几个自然段让人物面对问题并作出第一步回应，不用无关远期悬念再跳到平淡日常。
                不把有限篇幅花在完整操作步骤或重复的小动作上；展开有依据的关系压力、选择或信息变化。
                片段让读者看见问题与行动正在推进即可，不强求整章兑现、结尾钩子或抢写后续章节。
                如计划只支持顺利完成日常事务，忠于计划，不虚构暗恋、背叛、误会或意外强化开篇；这个上游限制由独立审阅提示作者。
                """;
    }

    public static String previewReviewRules(CreativeStrategyPolicy policy) {
        if (!gripping(policy)) return "";
        return """
                \n【强开篇片段审阅落点】
                检查最初几个自然段的当前压力、人物意图、已展开行动及阻力或信息差，文句通顺不能替代此检查。
                SCENE 中定位无关远期预告后切回无压力日常、重复解释或操作流程占据样例的问题；每条引用 body 连续原文，指出怎样压缩已有内容而非编新剧情。
                区分第一章计划本身没有开场压力与试写未落实已有设计，在 description 指明上游依据及限制。
                只检查已提供片段，未走到回报不等于整章兑现失败。
                """;
    }

    private CreativeStrategyGuide() {}
}
