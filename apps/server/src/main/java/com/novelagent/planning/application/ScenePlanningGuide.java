package com.novelagent.planning.application;

/** Scene preparation is part of the existing outline request, not a new Agent or approval stage. */
public final class ScenePlanningGuide {
    private ScenePlanningGuide() { }

    public static String planningRules() {
        return """
                【随大纲一次生成的场景底稿】
                每章 sceneOutline 是自由文本场景清单和关键场景展开，与章节大纲在本次请求一起输出，不等待另一次准备或合同请求。
                按因果和现场互动划分场景，同一地点的连续互动可是一场，回忆或概述按作用简写；不固定每章场景数，不为凑数量拆碎动作。
                用简洁标题或自然段逐场写清：视角与时空、参与者的眼前目标、阻力或信息差、关键行动与选择、转折、结束变化以及衔接。
                核心场景进一步说明冲突怎样由行动触发、人物怎样尝试和回应、什么让局面改变；可写对白意图，不提前写完整正文或长篇对话。
                场景不是复述 coreEvent，也不是逐动作流水账；关系双方有各自诉求，日常细节只展开会影响人物、信息或安排的部分。
                前三章与实际高潮优先明确行动和回报，其他章节按戏份简洁展开；篇幅服务故事，不追求固定字数、字段齐全或反转配额。
                复用具体人物姓名、经历、能力边界及已设计情节，不在场景底稿里另造过去来修补因果；未来计划不等于已发生或人物已知。
                OCCURRED 章节只归纳原文已发生场景，不改变次序、事实或结果；缺少依据注明局限，不补造原文对白。
                修订时只更新获授权或上游实际影响的场景，未受影响底稿保留。旧版缺少底稿，不等于获准改写全部章节；仅在本次获授权范围内补充。
                sceneOutlineNeedsUpdate 是服务端维护的依据变化提示，不由模型输出；sceneOutline 保留自由叙述，不输出嵌套场景字段、自评分或解释。
                """;
    }

    public static String writingRules() {
        return """
                【已保存场景底稿的使用】
                直接落实本章 sceneOutline 的关键行动、转折、结束变化与衔接，不再生成或展示另一个准备结果，不要求合同或逐场审批。
                底稿是规划不是正文；将对白意图和情节描述写成现场，不把清单、设计说明和检查条目写进小说。
                sceneOutlineNeedsUpdate=true 时底稿为空或依据已变化，旧文本仅供核对，不执行其中冲突或过期安排，以当前圣经、本章明确计划、有效事实和作者授权为准。
                没有场景底稿不授权改变故事事实或新增人物过去；试写只执行开头实际展开的场景，不提前完成整章或后续兑现。
                """;
    }
}
