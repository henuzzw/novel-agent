package com.novelagent.project.application;

import com.novelagent.project.domain.CreativeBoundaries;

/** Shared boundaries for planning and A/B/C; a realism instruction is not external fact verification. */
public final class CreativeBoundaryGuide {
    private CreativeBoundaryGuide() { }
    public static String render(CreativeBoundaries policy) {
        String world = switch (policy.effectiveWorld()) {
            case REALISTIC -> "现实写实：人物与日常情节可以虚构；正式赛事、制度、升学政策和奖励须符合对应地区、年代的现实规则。"
                    + "未提供可靠资料时不得宣称已核实，不捏造官方赛事、特殊资格或升学优惠；优先使用普通校内活动，必要处标为待核实。";
            case REALISTIC_FICTION -> "现实背景允许虚构活动：可以设计虚构校内赛或邀请赛，交代组织者、参赛条件、赛制、时间、资源和合理奖励。"
                    + "不冒充真实官方赛事，遵守现实物理、年龄、能力与社会条件。对手不能无规则依据决定正式试题。";
            case ALTERNATE -> "架空世界：可以在授权范围设计历史、制度和赛事，明确架空范围、限制与代价，保持已建立规则一致；不能临时修改规则帮助主角获胜。";
            case AUTO -> throw new IllegalStateException("未解析世界设定");
        };
        String scope = policy.effectiveScope() == CreativeBoundaries.AdaptationScope.RESTRUCTURE
                ? "允许重构主线：只在本次改编授权范围重排、扩展主线；KEEP、已确认设定和续写既往事实仍不能擅改。"
                : "保留核心主线：强化素材核心目标、人物关系与题材承诺，不将校园关系故事自动换成竞赛夺冠或其他题材。自动判断不授予重构权限。";
        return "\n【项目世界设定与改编边界】\n选择：" + policy.worldMode() + " / " + policy.adaptationScope()
                + "；实际采用：" + policy.effectiveWorld() + " / " + policy.effectiveScope()
                + "\n判断依据：" + (policy.reason().isBlank() ? "尚无完整原文判断，使用保守默认；不代表已核实现实规则。" : policy.reason())
                + "\n" + world + "\n" + scope
                + "\n大爽文策略控制期待与兑现，不扩大虚构或改编权限。优先从本书核心矛盾设计回报；校园关系可兑现公开维护、明确选择、信任恢复或关系主动权，不默认转成夺冠。"
                + "新增能力须有经历、学习过程、适用条件和限制；奖励规模服从人物能力与现实条件，不临时增加万能能力或特殊制度。"
                + "\n审阅区分内部自洽与现实核实；无可靠来源只能提出待核实问题，不能宣称制度真实或凭猜测修订。世界选择不改变已确认事实，设置切换不授权自动重写旧稿。\n";
    }
}
